"""Turns messy third-party cell values into canonical ones. Pure functions, rules driven by normalization_rules.json.

Every function returns (value, error_message). A bad cell never raises: it becomes a row-level validation error
so one malformed cell cannot abort a million-row file.
"""
import json
import re
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from functools import lru_cache
from pathlib import Path

RULES_PATH = Path(__file__).parent / "rules" / "normalization_rules.json"


@lru_cache(maxsize=1)
def rules() -> dict:
    return json.loads(RULES_PATH.read_text(encoding="utf-8"))


def _clean(v) -> str:
    return "" if v is None else str(v).strip()


def _key(s: str) -> str:
    return re.sub(r"[^A-Z0-9]+", "_", s.upper()).strip("_")


# ── relation ──────────────────────────────────────────────────────────────────────────────────────────────
def normalize_relation(raw, source_key: str = "") -> tuple[str | None, str | None]:
    text = _clean(raw)
    if not text:
        return None, None
    k = _key(text)
    r = rules()
    if k in r["relation_codes"]:
        return k, None
    per_source = r["relation_by_source"].get(source_key.upper(), {})
    if k in per_source:
        return per_source[k], None
    # numbered siblings: "SON-II", "Son 2", "DAUGHTER_III"
    m = re.fullmatch(r"(SON|DAUGHTER)_?(I{1,3}|IV|[1-4])", k)
    if m:
        n = r["sibling_numbered"]["suffixes"][m.group(2)]
        return (m.group(1) if n == 1 else f"{m.group(1)}_{('', 'I', 'II', 'III', 'IV')[n]}"), None
    if k in r["relation_synonyms"]:
        return r["relation_synonyms"][k], None
    return None, f"unrecognised relation '{text}'"


# ── dates ─────────────────────────────────────────────────────────────────────────────────────────────────
_EXCEL_EPOCH = date(1899, 12, 30)
MAX_AMOUNT = Decimal("1e12")           # no sum insured or salary is a trillion; bigger means a data error


def normalize_date(raw, dob_format: str | None = None, today: date | None = None) -> tuple[str | None, str | None]:
    text = _clean(raw)
    if not text:
        return None, None
    today = today or date.today()
    if re.fullmatch(r"\d{5}(\.0+)?", text):                        # Excel serial date
        serial = int(float(text))
        if 1 <= serial <= 80000:
            return (_EXCEL_EPOCH + timedelta(days=serial)).isoformat(), None
    formats = ([dob_format] if dob_format else []) + rules()["date_formats"]
    for fmt in formats:
        try:
            d = datetime.strptime(text, fmt).date()
        except ValueError:
            continue
        if "%y" in fmt and d.year > today.year:                    # two-digit year pivot: 85 -> 1985, not 2085
            d = d.replace(year=d.year - 100)
        return d.isoformat(), None
    return None, f"unreadable date '{text}'"


# ── simple cells ──────────────────────────────────────────────────────────────────────────────────────────
def normalize_employee_code(raw) -> str | None:
    text = _clean(raw).lstrip("'")                                  # Excel "text" marker
    if re.fullmatch(r"\d+\.0+", text):                              # 1234.0 from a numeric Excel cell
        text = text.split(".")[0]
    text = re.sub(r"\s+", "", text)
    return text or None


def normalize_gender(raw) -> tuple[str | None, str | None]:
    text = _clean(raw)
    if not text:
        return None, None
    g = rules()["gender"].get(_key(text))
    return (g, None) if g else (None, f"unrecognised gender '{text}'")


def normalize_amount(raw) -> tuple[str | None, str | None]:
    text = _clean(raw)
    if not text:
        return None, None
    t = text
    for p in rules()["amount_prefixes"]:
        if t.upper().startswith(p.upper()):
            t = t[len(p):]
    t = t.replace(",", "").strip()
    try:
        d = Decimal(t)
    except InvalidOperation:
        return None, f"not a number '{text}'"
    if not d.is_finite() or abs(d) >= MAX_AMOUNT:
        return None, f"not a usable amount '{text}'"
    try:
        return format(d.quantize(Decimal("0.01")), "f"), None
    except InvalidOperation:
        return None, f"not a usable amount '{text}'"


def normalize_pan(raw) -> str | None:
    t = re.sub(r"\s+", "", _clean(raw)).upper()
    return t or None


def normalize_mobile(raw) -> str | None:
    digits = re.sub(r"\D", "", _clean(raw))
    if len(digits) == 12 and digits.startswith("91"):
        digits = digits[2:]
    elif len(digits) == 11 and digits.startswith("0"):
        digits = digits[1:]
    return digits or None


def normalize_email(raw) -> str | None:
    t = _clean(raw).lower()
    return t or None


def split_full_name(raw) -> tuple[str | None, str | None]:
    parts = _clean(raw).split()
    if not parts:
        return None, None
    return " ".join(parts[:-1]) or parts[0], (parts[-1] if len(parts) > 1 else None)


# ── whole row ─────────────────────────────────────────────────────────────────────────────────────────────
def normalize_row(raw: dict, *, source_key: str = "", options: dict | None = None,
                  default_effective_date: str | None = None, today: date | None = None) -> tuple[dict, list[dict]]:
    """raw: canonical-keyed cells straight from the file. Returns (normalised row, parse-level errors)."""
    options = options or {}
    errors: list[dict] = []

    def keep(field, value, err):
        if err:
            errors.append({"field": field, "rule": f"INVALID_{field.upper()}", "message": err, "value": _clean(raw.get(field))})
        return value

    out: dict = {"employee_code": normalize_employee_code(raw.get("employee_code"))}
    rel, err = normalize_relation(raw.get("relation"), source_key)
    out["relation_code"] = keep("relation", rel, err)

    first, last = _clean(raw.get("first_name")) or None, _clean(raw.get("last_name")) or None
    if not first and raw.get("full_name"):
        first, last = split_full_name(raw.get("full_name"))
    out["first_name"], out["last_name"] = first, last

    dob, err = normalize_date(raw.get("dob"), options.get("dob_format"), today)
    out["dob"] = keep("dob", dob, err)
    eff, err = normalize_date(raw.get("effective_date"), options.get("date_format"), today)
    out["effective_date"] = keep("effective_date", eff, err) or default_effective_date

    gender, err = normalize_gender(raw.get("gender"))
    out["gender"] = keep("gender", gender, err)
    for f in ("sum_insured", "annual_salary"):
        v, err = normalize_amount(raw.get(f))
        out[f] = keep(f, v, err)

    out["pan"] = normalize_pan(raw.get("pan"))
    out["mobile"] = normalize_mobile(raw.get("mobile"))
    out["email"] = normalize_email(raw.get("email"))
    out["grade"] = _clean(raw.get("grade")) or None
    out["designation"] = _clean(raw.get("designation")) or None
    return out, errors
