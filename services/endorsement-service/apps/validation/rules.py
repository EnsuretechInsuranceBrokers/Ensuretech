"""Row-level validation rules, implemented twice on purpose:

* ``issues_vectorized``  - pandas, for 500-row chunks (bulk path).
* ``issues_scalar``      - plain Python, for the interactive GUI (no DataFrame overhead, <50 ms per cell edit).

tests/test_rules_parity.py fuzzes both against each other so they cannot drift. Set-based rules (duplicates, existing
members, family constraints) live in batch.py and are shared by both paths.
"""
import re
from dataclasses import dataclass
from datetime import date

import pandas as pd

PAN_RE = re.compile(r"^[A-Z]{5}[0-9]{4}[A-Z]$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
MOBILE_RE = re.compile(r"^[6-9][0-9]{9}$")
MAX_AGE = 100

COLUMNS = ["employee_code", "relation_code", "first_name", "last_name", "dob", "gender", "pan", "mobile", "email",
           "grade", "designation", "annual_salary", "sum_insured", "effective_date", "number_of_time_salary", "employee_premium", "employer_premium"]
UPDATABLE = ["first_name", "last_name", "dob", "gender", "pan", "mobile", "email", "grade", "designation",
             "annual_salary", "sum_insured"]
REQUIRED = {
    "ADD": ["employee_code", "relation_code", "first_name", "dob", "sum_insured", "effective_date"],
    "REMOVE": ["employee_code", "relation_code", "effective_date"],
    "UPDATE": ["employee_code", "relation_code"],
}
# Legacy salary-multiple raters compute the sum insured from salary x number of times salary, and leave the Suminsured column blank:
# for those rows sum_insured is not required on ADD.
SALARY_DERIVED_NOTE = "sum_insured is required for ADD unless annual salary and number of times salary are given (the rater computes it)"
# parse-time error rule code -> canonical column it concerns (so a bad cell is not also reported as "missing")
PARSE_FIELD = {"relation": "relation_code", "dob": "dob", "effective_date": "effective_date", "gender": "gender",
               "sum_insured": "sum_insured", "annual_salary": "annual_salary", "employee_code": "employee_code",
               "number_of_time_salary": "number_of_time_salary", "employee_premium": "employee_premium", "employer_premium": "employer_premium"}


@dataclass(frozen=True)
class Issue:
    row_index: int
    field: str
    rule: str
    severity: str
    message: str
    value: str = ""


def _blank(v) -> bool:
    return v is None or (isinstance(v, float) and v != v) or str(v).strip() == ""


def _age(dob: date, on: date) -> int:
    return on.year - dob.year - ((on.month, on.day) < (dob.month, dob.day))


def _parse_issues(row_index: int, errors: list) -> tuple[list[Issue], set[str]]:
    out, bad = [], set()
    for e in errors or []:
        out.append(Issue(row_index, e["field"], e["rule"], "ERROR", e["message"], str(e.get("value", ""))))
        bad.add(PARSE_FIELD.get(e["field"], e["field"]))
    return out, bad


# ── scalar ───────────────────────────────────────────────────────────────────────────────────────────────────
def issues_scalar(row: dict, op: str, today: date, row_index: int = 0) -> list[Issue]:
    errors = row.get("_errors") or []
    out, parse_bad = _parse_issues(row_index, errors)

    def add(field, rule, msg, sev="ERROR"):
        out.append(Issue(row_index, field, rule, sev, msg, "" if _blank(row.get(field)) else str(row.get(field))))

    derived = not _blank(row.get("annual_salary")) and not _blank(row.get("number_of_time_salary")) \
        and "annual_salary" not in parse_bad and "number_of_time_salary" not in parse_bad
    for f in REQUIRED[op]:
        if f == "sum_insured" and derived:
            continue
        if _blank(row.get(f)) and f not in parse_bad:
            add(f, "REQUIRED_FIELD", f"{f} is required for {op}")
    if op == "UPDATE" and all(_blank(row.get(f)) for f in UPDATABLE) and not parse_bad & set(UPDATABLE):
        add("", "NOTHING_TO_UPDATE", "UPDATE row changes nothing")

    dob = date.fromisoformat(row["dob"]) if not _blank(row.get("dob")) and "dob" not in parse_bad else None
    eff = date.fromisoformat(row["effective_date"]) if not _blank(row.get("effective_date")) \
        and "effective_date" not in parse_bad else None
    if dob:
        if dob > today:
            add("dob", "DOB_IN_FUTURE", "date of birth is in the future")
        elif eff and dob > eff:
            add("dob", "DOB_AFTER_EFFECTIVE_DATE", "date of birth is after the effective date")
        elif _age(dob, eff or today) > MAX_AGE:
            add("dob", "AGE_OUT_OF_RANGE", f"age exceeds {MAX_AGE}")
    if not _blank(row.get("pan")) and not PAN_RE.match(str(row["pan"])):
        add("pan", "INVALID_PAN", "PAN must look like ABCDE1234F")
    if not _blank(row.get("email")) and not EMAIL_RE.match(str(row["email"])):
        add("email", "INVALID_EMAIL", "email address looks invalid", "WARN")
    if not _blank(row.get("mobile")) and not MOBILE_RE.match(str(row["mobile"])):
        add("mobile", "INVALID_MOBILE", "mobile must be 10 digits starting 6-9", "WARN")
    if op == "ADD" and not _blank(row.get("sum_insured")) and "sum_insured" not in parse_bad \
            and float(row["sum_insured"]) <= 0:
        add("sum_insured", "SUM_INSURED_NOT_POSITIVE", "sum insured must be greater than zero")
    if not _blank(row.get("annual_salary")) and "annual_salary" not in parse_bad and float(row["annual_salary"]) < 0:
        add("annual_salary", "SALARY_NEGATIVE", "annual salary cannot be negative")
    if not _blank(row.get("number_of_time_salary")) and "number_of_time_salary" not in parse_bad and float(row["number_of_time_salary"]) <= 0:
        add("number_of_time_salary", "MULTIPLE_NOT_POSITIVE", "number of times salary must be greater than zero")
    for f in ("employee_premium", "employer_premium"):
        if not _blank(row.get(f)) and f not in parse_bad and float(row[f]) < 0:
            add(f, "PREMIUM_NEGATIVE", f"{f.replace('_', ' ')} cannot be negative")
    return out


# ── vectorized ───────────────────────────────────────────────────────────────────────────────────────────────
def to_frame(rows: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame([{c: r.get(c) for c in COLUMNS} for r in rows], columns=COLUMNS, dtype=object)
    df["_errors"] = pd.Series([r.get("_errors") or [] for r in rows], dtype=object)
    return df


def issues_vectorized(df: pd.DataFrame, op: str, today: date) -> list[Issue]:
    out: list[Issue] = []
    n = len(df)
    if n == 0:
        return out
    blank = {c: df[c].isna() | df[c].astype(str).str.strip().eq("") for c in COLUMNS}
    parse_bad = [set() for _ in range(n)]
    for i, errs in enumerate(df["_errors"]):
        issues, bad = _parse_issues(i, errs)
        out.extend(issues)
        parse_bad[i] = bad
    pb = {c: pd.Series([c in b for b in parse_bad], index=df.index) for c in COLUMNS}

    def emit(mask, field, rule, msg, sev="ERROR"):
        for i in df.index[mask.fillna(False).astype(bool)]:
            v = df.at[i, field] if field else ""
            out.append(Issue(int(i), field, rule, sev, msg, "" if _blank(v) else str(v)))

    derived = ~blank["annual_salary"] & ~pb["annual_salary"] & ~blank["number_of_time_salary"] & ~pb["number_of_time_salary"]
    for f in REQUIRED[op]:
        mask = blank[f] & ~pb[f]
        if f == "sum_insured":
            mask = mask & ~derived
        emit(mask, f, "REQUIRED_FIELD", f"{f} is required for {op}")
    if op == "UPDATE":
        none_set = pd.concat([blank[f] for f in UPDATABLE], axis=1).all(axis=1)
        any_bad = pd.concat([pb[f] for f in UPDATABLE], axis=1).any(axis=1)
        emit(none_set & ~any_bad, "", "NOTHING_TO_UPDATE", "UPDATE row changes nothing")

    dob = pd.to_datetime(df["dob"].where(~blank["dob"] & ~pb["dob"]), format="%Y-%m-%d", errors="coerce")
    eff = pd.to_datetime(df["effective_date"].where(~blank["effective_date"] & ~pb["effective_date"]),
                         format="%Y-%m-%d", errors="coerce")
    today_ts = pd.Timestamp(today)
    has_dob = dob.notna()
    future = has_dob & (dob > today_ts)
    after_eff = has_dob & ~future & eff.notna() & (dob > eff)
    ref = eff.fillna(today_ts)
    age = ref.dt.year - dob.dt.year - ((ref.dt.month * 100 + ref.dt.day) < (dob.dt.month * 100 + dob.dt.day)).astype(int)
    too_old = has_dob & ~future & ~after_eff & (age > MAX_AGE)
    emit(future, "dob", "DOB_IN_FUTURE", "date of birth is in the future")
    emit(after_eff, "dob", "DOB_AFTER_EFFECTIVE_DATE", "date of birth is after the effective date")
    emit(too_old, "dob", "AGE_OUT_OF_RANGE", f"age exceeds {MAX_AGE}")

    def matches(col, rx):
        s = df[col].where(~blank[col]).astype(str)
        return ~blank[col] & ~s.str.match(rx.pattern)

    emit(matches("pan", PAN_RE), "pan", "INVALID_PAN", "PAN must look like ABCDE1234F")
    emit(matches("email", EMAIL_RE), "email", "INVALID_EMAIL", "email address looks invalid", "WARN")
    emit(matches("mobile", MOBILE_RE), "mobile", "INVALID_MOBILE", "mobile must be 10 digits starting 6-9", "WARN")

    si = pd.to_numeric(df["sum_insured"].where(~blank["sum_insured"] & ~pb["sum_insured"]), errors="coerce")
    sal = pd.to_numeric(df["annual_salary"].where(~blank["annual_salary"] & ~pb["annual_salary"]), errors="coerce")
    if op == "ADD":
        emit(si.notna() & (si <= 0), "sum_insured", "SUM_INSURED_NOT_POSITIVE", "sum insured must be greater than zero")
    emit(sal.notna() & (sal < 0), "annual_salary", "SALARY_NEGATIVE", "annual salary cannot be negative")
    mult = pd.to_numeric(df["number_of_time_salary"].where(~blank["number_of_time_salary"] & ~pb["number_of_time_salary"]), errors="coerce")
    emit(mult.notna() & (mult <= 0), "number_of_time_salary", "MULTIPLE_NOT_POSITIVE", "number of times salary must be greater than zero")
    for f in ("employee_premium", "employer_premium"):
        p = pd.to_numeric(df[f].where(~blank[f] & ~pb[f]), errors="coerce")
        emit(p.notna() & (p < 0), f, "PREMIUM_NEGATIVE", f"{f.replace('_', ' ')} cannot be negative")
    return out
