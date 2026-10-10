"""Streaming CSV / Excel (.xlsx) -> chunks of normalised rows. Never loads the whole file; memory is bounded by one chunk plus the
set of already-closed employee codes."""
import codecs
import csv
import hashlib
import io
import json
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import date, datetime, time

from .normalize import normalize_employee_code, normalize_relation, normalize_row
from .schema import DUAL_FIELDS, norm_header


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


XLSX_MAGIC = b"PK\x03\x04"
MAX_XLSX_UNCOMPRESSED = 300 * 1024 * 1024
MAX_XLSX_RATIO = 200


def _cell(v) -> str:
    """One spreadsheet cell as the text a CSV would have carried. Dates become ISO; whole numbers lose Excel's trailing .0."""
    if v is None:
        return ""
    if isinstance(v, datetime):
        return v.date().isoformat() if v.time() == time(0, 0) else v.isoformat()
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, float) and v.is_integer() and abs(v) < 1e15:
        return str(int(v))
    return str(v).strip()


def _xlsx_reader(binary):
    """First worksheet of an .xlsx, streamed (read-only mode). A .xlsx is a zip, so refuse decompression bombs before opening."""
    try:
        with zipfile.ZipFile(binary) as z:
            infos = z.infolist()
            total = sum(i.file_size for i in infos)
            packed = sum(i.compress_size for i in infos) or 1
            if total > MAX_XLSX_UNCOMPRESSED or total / packed > MAX_XLSX_RATIO:
                raise FileProblem("the spreadsheet expands to an unreasonable size")
        binary.seek(0)
        from openpyxl import load_workbook
        wb = load_workbook(binary, read_only=True, data_only=True)
        sheet = wb.worksheets[0]
    except FileProblem:
        raise
    except Exception as e:                    # noqa: BLE001  openpyxl/zipfile raise many types for a corrupt file
        raise FileProblem(f"not a readable Excel file: {type(e).__name__}") from e
    return ([_cell(v) for v in row] for row in sheet.iter_rows(values_only=True))


def _reader(binary):
    head = binary.read(4)
    binary.seek(0)
    if head == XLSX_MAGIC:
        return _xlsx_reader(binary)
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


def _merge_dual(raw: dict, source_key: str) -> dict:
    """Legacy two-block layout: Self rows take the Employee* columns, every other relation takes the Insured Member* columns.
    A dependant is never silently given the employee's DOB or name: a blank member cell stays blank and fails validation."""
    if not any(k.startswith(("emp_", "mem_")) for k in raw):
        return raw
    rel, _ = normalize_relation(raw.get("relation"), source_key)
    is_self = rel == "SELF"
    out = {k: v for k, v in raw.items() if not k.startswith(("emp_", "mem_"))}
    for canon, (emp, mem) in DUAL_FIELDS.items():
        own, other = (emp, mem) if is_self else (mem, emp)
        v = raw.get(own) or (raw.get(other) if is_self else "") or raw.get(canon, "")      # Self may fall back to the member block
        if v:
            out[canon] = v
    return out


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
    family_doj: dict[str, str] = {}
    family_salary: dict[str, tuple[str, str]] = {}
    try:
        for line_no, cells in enumerate(reader, start=2):
            if not any(c.strip() for c in cells):
                continue
            total += 1
            if total > max_rows:
                raise TooManyRows(f"file has more than {max_rows} rows")
            raw = {f: cells[i].strip() for i, f in enumerate(fields) if f and i < len(cells)}
            raw = _merge_dual(raw, source_key)
            code_key = normalize_employee_code(raw.get("employee_code"))
            if raw.get("date_of_joining") and code_key:
                family_doj[code_key] = raw["date_of_joining"]
            if not raw.get("effective_date") and code_key in family_doj:
                raw["effective_date"] = family_doj[code_key]          # fallback only: an Effective Date column always wins
            if op_type == "ADD" and code_key:
                # Legacy raters read salary and the salary multiple from the EMPLOYEE for every member: a dependant row does not repeat them.
                if raw.get("annual_salary") and raw.get("number_of_time_salary"):
                    family_salary[code_key] = (raw["annual_salary"], raw["number_of_time_salary"])
                elif code_key in family_salary and not raw.get("annual_salary"):
                    raw["annual_salary"] = family_salary[code_key][0]
                    if not raw.get("number_of_time_salary"):
                        raw["number_of_time_salary"] = family_salary[code_key][1]
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
