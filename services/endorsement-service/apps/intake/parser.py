"""Streaming CSV -> chunks of normalised rows. Never loads the whole file; memory is bounded by one chunk plus the
set of already-closed employee codes."""
import codecs
import csv
import hashlib
import io
import json
from collections.abc import Iterator
from dataclasses import dataclass, field

from .normalize import normalize_row
from .schema import norm_header


class FileProblem(Exception):
    """The file itself is unusable (bad encoding, no header, too many rows)."""


class TooManyRows(FileProblem):
    pass


@dataclass
class ParsedRow:
    source_line: int
    raw: dict
    normalised: dict
    errors: list
    row_hash: str


@dataclass
class Chunk:
    index: int
    rows: list[ParsedRow] = field(default_factory=list)


def _detect_encoding(binary) -> str:
    """UTF-8 (with or without BOM) when the first MB decodes cleanly, otherwise Windows-1252 (Excel exports)."""
    head = binary.read(1024 * 1024)
    binary.seek(0)
    dec = codecs.getincrementaldecoder("utf-8-sig")()
    try:
        dec.decode(head, final=False)          # final=False tolerates a multi-byte char cut at the 1 MB edge
        return "utf-8-sig"
    except UnicodeDecodeError:
        return "cp1252"


def _reader(binary):
    enc = _detect_encoding(binary)
    text = io.TextIOWrapper(binary, encoding=enc, errors="replace", newline="")
    sample = text.read(8192)
    text.seek(0)
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
    except csv.Error:
        dialect = csv.excel
    return csv.reader(text, dialect)


def read_header(binary) -> list[str]:
    try:
        header = next(_reader(binary), None)
    except csv.Error as e:
        raise FileProblem(f"not a readable CSV: {e}") from e
    if not header or not any(c.strip() for c in header):
        raise FileProblem("file has no header row")
    return [c.strip() for c in header]


def row_hash(op_type: str, normalised: dict) -> str:
    return hashlib.sha256(json.dumps({"op": op_type, **normalised}, sort_keys=True, default=str).encode()).hexdigest()


def iter_chunks(binary, *, column_map: dict[str, str], op_type: str, source_key: str = "", options: dict | None = None,
                default_effective_date: str | None = None, target_rows: int = 500,
                max_rows: int = 1_000_000) -> Iterator[Chunk]:
    """Yield chunks of ~target_rows. A chunk only closes when the employee changes, so an employee and their
    dependents are never split across chunks (family rules need the whole family). Rows are expected to be grouped
    by employee; a code that reappears later is flagged on that row instead of silently splitting a family."""
    hard_cap = max(target_rows * 4, 50)
    lookup = {norm_header(h): f for h, f in column_map.items()}
    reader = _reader(binary)
    try:
        header = next(reader, None)
    except csv.Error as e:
        raise FileProblem(f"not a readable CSV: {e}") from e
    if not header:
        raise FileProblem("file has no header row")
    fields = [lookup.get(norm_header(h)) for h in header]

    chunk, index, total = Chunk(0), 0, 0
    last_code, closed = object(), set()      # sentinel: no previous row yet
    try:
        for line_no, cells in enumerate(reader, start=2):
            if not any(c.strip() for c in cells):
                continue
            total += 1
            if total > max_rows:
                raise TooManyRows(f"file has more than {max_rows} rows")
            raw = {f: cells[i].strip() for i, f in enumerate(fields) if f and i < len(cells)}
            norm, errors = normalize_row(raw, source_key=source_key, options=options,
                                         default_effective_date=default_effective_date)
            code = norm["employee_code"]
            if code != last_code:
                if chunk.rows and len(chunk.rows) >= target_rows:
                    yield chunk
                    index += 1
                    chunk = Chunk(index)
                if isinstance(last_code, str):
                    closed.add(last_code)
                last_code = code
                if code and code in closed:
                    errors.append({"field": "employee_code", "rule": "EMPLOYEE_ROWS_NOT_CONTIGUOUS", "value": code,
                                   "message": "rows for one employee must be adjacent in the file"})
            chunk.rows.append(ParsedRow(line_no, raw, norm, errors, row_hash(op_type, norm)))
            if len(chunk.rows) >= hard_cap:
                yield chunk
                index += 1
                chunk = Chunk(index)
    except csv.Error as e:
        raise FileProblem(f"CSV parse error near line {line_no}: {e}") from e
    if chunk.rows:
        yield chunk
