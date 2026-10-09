"""The live grid: the whole sheet on screen is checked on every pause in typing, in one pass, with pandas.

Per-row checks (gui.check_row) cannot see across rows. The grid check can: duplicates inside the grid, one PAN used by two people, an employee's
whole family against the policy's family rules, a spouse who joins an employee who is already covered. It runs the very same rule code the
million-row file path runs (apps.validation.batch.validate_rows), on the grid's rows instead of a file's chunk, so a grid that shows no errors
cannot fail validation after submit for a reason the grid could have known. The rows are not saved by this call.

Alongside the verdict it returns what a person filling in 80 employees wants to see while doing it: how far along they are, who is
missing what, the age and sum-insured spread, the family sizes, the mistakes grouped by kind.
"""
import logging
from datetime import date
from types import SimpleNamespace

import pandas as pd
from django.utils import timezone

from apps.computation.policy_client import get_policy_client
from apps.validation.batch import validate_rows
from apps.validation.rules import REQUIRED

from .normalize import normalize_row

log = logging.getLogger(__name__)

AGE_BANDS = [(0, 17, "0-17"), (18, 25, "18-25"), (26, 35, "26-35"), (36, 45, "36-45"), (46, 55, "46-55"), (56, 65, "56-65"), (66, 200, "66+")]
KEY_FIELDS = ["employee_code", "relation_code", "first_name", "dob", "gender", "sum_insured", "effective_date"]


class _NoFamilyRules:
    """Stands in for policy-service when the family check is switched off or the service cannot be reached."""

    def validate_families(self, *_a, **_k):
        return {}


def _blank(v) -> bool:
    return v is None or str(v).strip() == ""


def _is_blank_row(raw: dict) -> bool:
    return all(_blank(v) for k, v in raw.items() if k != "row_key")


def _bands(ages: pd.Series) -> dict:
    counts = {label: int(((ages >= lo) & (ages <= hi)).sum()) for lo, hi, label in AGE_BANDS}
    return {k: v for k, v in counts.items() if v}


def analyse(session, op: str, rows: list[dict], *, today: date | None = None, families: bool = True) -> dict:
    """@param rows the grid, top to bottom; each a dict of cell values (any may be blank), optionally with a "row_key".
    @return {"rows": [...one per input row, in order...], "summary": {...}, "checks": {...}}"""
    today = today or date.today()
    default_eff = session.effective_date.isoformat() if session.effective_date else None
    live: list[int] = []                                  # grid row positions that have something typed in them
    staged = []
    for pos, raw in enumerate(rows):
        if _is_blank_row(raw):
            continue
        cells = {k: v for k, v in raw.items() if k != "row_key"}
        norm, errs = normalize_row(cells, default_effective_date=default_eff, today=today)
        staged.append(SimpleNamespace(row_index=len(live), source_line=pos + 1, normalised_payload={**norm, "_errors": errs}))
        live.append(pos)

    job = SimpleNamespace(op_type=op, policy_id=session.policy_id, broker_id=session.broker_id, created_at=timezone.now())
    families_checked = bool(families and session.policy_facts.get("requires_family_construct") and op == "ADD")
    client = get_policy_client() if families_checked else _NoFamilyRules()
    try:
        found = validate_rows(job, staged, session.policy_facts, client, today, persist=False)
    except Exception as e:                                  # the family rules live in another service: a hiccup there must not blank the grid
        if not families_checked:
            raise
        log.warning("family rules unavailable for the grid check: %s", e)
        families_checked = False
        found = validate_rows(job, staged, session.policy_facts, _NoFamilyRules(), today, persist=False)

    by_row: dict[int, list[dict]] = {}
    for i in found:
        by_row.setdefault(live[i.row_index], []).append({"field": i.field, "rule": i.rule, "severity": i.severity, "message": i.message})

    out = []
    for pos, raw in enumerate(rows):
        issues = by_row.get(pos, [])
        blank = _is_blank_row(raw)
        out.append({"index": pos, "row_key": raw.get("row_key"), "blank": blank, "issues": issues,
                    "valid": (not blank) and not any(i["severity"] == "ERROR" for i in issues)})

    return {"rows": out, "summary": summarise(session, op, [s.normalised_payload for s in staged], [live[i] for i in range(len(live))], by_row, len(rows), today),
            "checks": {"family_rules": families_checked, "rules": "same rules as the file path"}}


def summarise(session, op, payloads: list[dict], positions: list[int], by_row: dict[int, list[dict]], grid_rows: int, today: date) -> dict:
    """What the grid looks like as a whole, worked out with pandas over the normalised rows."""
    n = len(payloads)
    errored = sum(any(i["severity"] == "ERROR" for i in by_row.get(p, [])) for p in positions)
    warned = sum(any(i["severity"] == "WARN" for i in by_row.get(p, [])) and not any(i["severity"] == "ERROR" for i in by_row.get(p, [])) for p in positions)
    s = {"rows_typed": n, "rows_blank": grid_rows - n, "rows_with_errors": errored, "rows_with_warnings": warned, "rows_clean": n - errored - warned,
         "expected_employees": session.expected_employees}
    problems = [i for p in positions for i in by_row.get(p, [])]
    if problems:
        df = pd.DataFrame(problems)
        s["by_rule"] = [{"rule": r, "severity": sv, "count": int(c)} for (r, sv), c in df.groupby(["rule", "severity"]).size().sort_values(ascending=False).items()]
        s["by_field"] = {str(k) or "(row)": int(v) for k, v in df[df.severity == "ERROR"].field.replace("", "(whole row)").value_counts().items()}
    else:
        s["by_rule"], s["by_field"] = [], {}
    if not n:
        s.update({"employees_entered": 0, "completeness": {}, "relations": {}, "ages": None, "ages_by_band": {}, "family_sizes": {}, "sum_insured": None})
        return s

    df = pd.DataFrame(payloads)
    for c in KEY_FIELDS:
        if c not in df:
            df[c] = None
    emp = df["employee_code"].where(df["employee_code"].notna() & df["employee_code"].astype(str).ne(""))
    s["employees_entered"] = int(emp.nunique())
    required = [("relation_code" if f == "relation" else f) for f in REQUIRED[op]]
    s["completeness"] = {c: round(float(df[c].notna().mean() * 100), 1) for c in required if c in df}
    s["relations"] = {str(k): int(v) for k, v in df["relation_code"].value_counts().items()}
    # ages on the day cover starts
    dob = pd.to_datetime(df["dob"], format="%Y-%m-%d", errors="coerce")
    eff = pd.to_datetime(df["effective_date"], format="%Y-%m-%d", errors="coerce").fillna(pd.Timestamp(today))
    age = (eff.dt.year - dob.dt.year - ((eff.dt.month * 100 + eff.dt.day) < (dob.dt.month * 100 + dob.dt.day)).astype("Int64"))
    age = age.dropna().astype(int)
    s["ages"] = {"min": int(age.min()), "median": float(age.median()), "max": int(age.max()), "mean": round(float(age.mean()), 1)} if len(age) else None
    s["ages_by_band"] = _bands(age) if len(age) else {}
    s["family_sizes"] = {str(int(k)): int(v) for k, v in emp.groupby(emp).size().value_counts().sort_index().items()} if emp.notna().any() else {}
    si = pd.to_numeric(df["sum_insured"], errors="coerce").dropna()
    s["sum_insured"] = ({"count": len(si), "total": round(float(si.sum()), 2), "mean": round(float(si.mean()), 2), "min": float(si.min()), "max": float(si.max())}
                        if len(si) else None)
    return s
