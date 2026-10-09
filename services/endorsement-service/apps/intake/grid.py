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
from django.db.models import Q
from django.utils import timezone

from apps.computation.policy_client import get_policy_client
from apps.validation.batch import validate_rows
from apps.validation.rules import REQUIRED

from .models import GuiRow
from .normalize import normalize_row

log = logging.getLogger(__name__)

AGE_BANDS = [(0, 17, "0-17"), (18, 25, "18-25"), (26, 35, "26-35"), (36, 45, "36-45"), (46, 55, "46-55"), (56, 65, "56-65"), (66, 200, "66+")]
SCALAR_BELOW = 60          # a check of this many rows or fewer runs the row rules as plain Python: pandas has a fixed cost that dwarfs a few rows
KEY_FIELDS = ["employee_code", "relation_code", "first_name", "dob", "gender", "sum_insured", "effective_date"]


class _NoFamilyRules:
    """Stands in for policy-service when the family check is switched off or the service cannot be reached."""

    def validate_families(self, *_a, **_k):
        return {}


def _blank(v) -> bool:
    return v is None or str(v).strip() == ""


def _is_blank_row(raw: dict) -> bool:
    return all(_blank(v) for k, v in raw.items() if k != "row_key" and not k.startswith("_"))


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
        cells = {k: v for k, v in raw.items() if k != "row_key" and not k.startswith("_")}
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


# ── one row at a time ────────────────────────────────────────────────────────────────────────────────────────────────────
# The grid does not send itself on every keystroke. The server keeps the rows (GuiRow); the browser sends the ONE row that changed.
# Almost every rule looks at that row alone. The few that look across rows (the same person twice, one PAN on two people, a family
# against the policy's rules) only ever look at rows sharing an employee code or a PAN with it, so exactly those rows are checked
# again, never the whole grid. The answer carries the row's verdict, the verdicts of any OTHER row that changed because of it, and the
# summary, computed over the stored rows without checking anything again.

def _pan(payload: dict):
    return (payload.get("pan") or "").strip() or None


def _recheck(session, op: str, rows: list, today: date, families: bool) -> tuple[dict[str, list[dict]], bool]:
    """Check exactly `rows` (stored GuiRows, together), return {row_key: issues}. Normalises from the raw cells every time, so what is checked is
    what was typed, never a stale copy."""
    default_eff = session.effective_date.isoformat() if session.effective_date else None
    staged = []
    for i, r in enumerate(rows):
        norm, errs = normalize_row({k: v for k, v in r.raw.items() if not k.startswith("_")}, default_effective_date=default_eff, today=today)
        staged.append(SimpleNamespace(row_index=i, source_line=i + 1, normalised_payload={**norm, "_errors": errs}))
    job = SimpleNamespace(op_type=op, policy_id=session.policy_id, broker_id=session.broker_id, created_at=timezone.now())
    use_families = bool(families and session.policy_facts.get("requires_family_construct") and op == "ADD")
    try:
        found = validate_rows(job, staged, session.policy_facts, get_policy_client() if use_families else _NoFamilyRules(), today, persist=False, scalar_rules=len(rows) <= SCALAR_BELOW)
    except Exception as e:
        if not use_families:
            raise
        log.warning("family rules unavailable for the grid check: %s", e)
        use_families = False
        found = validate_rows(job, staged, session.policy_facts, _NoFamilyRules(), today, persist=False, scalar_rules=len(rows) <= SCALAR_BELOW)
    out: dict[str, list[dict]] = {r.row_key: [] for r in rows}
    for i in found:
        out[rows[i.row_index].row_key].append({"field": i.field, "rule": i.rule, "severity": i.severity, "message": i.message})
    for r, st in zip(rows, staged):
        r.normalised = {k: v for k, v in st.normalised_payload.items() if k != "_errors"}
        r.employee_code = st.normalised_payload.get("employee_code") or ""
        r.relation_code = st.normalised_payload.get("relation_code") or ""
    return out, use_families


def _verdict(row: GuiRow) -> dict:
    return {"row_key": row.row_key, "blank": False, "issues": row.issues, "valid": not any(i["severity"] == "ERROR" for i in row.issues)}


def _order(r: GuiRow):
    return (r.raw.get("_pos", 10**9), r.id)


def _scope(session, op: str, codes: set, pans: set, own_pk=None) -> list:
    """The rows whose verdict can depend on the edited row: those sharing an employee code or a PAN with it, and then (a row's PAN clash
    changes whether its family is asked about at all) those sharing one with THEM, until nothing new turns up. Almost always one family."""
    seen: dict = {}
    codes, pans = set(codes), set(pans)
    while True:
        q = Q()
        if codes:
            q |= Q(employee_code__in=codes)
        if pans:
            q |= Q(normalised__pan__in=list(pans))
        if own_pk:
            q |= Q(pk=own_pk)
        found = {r.pk: r for r in GuiRow.objects.filter(session=session, op_type=op).filter(q)} if q else {}
        new = {pk: r for pk, r in found.items() if pk not in seen}
        if not new:
            break
        seen.update(new)
        codes |= {r.employee_code for r in new.values() if r.employee_code}
        pans |= {p for p in (_pan(r.normalised) for r in new.values()) if p}
        own_pk = None
    return sorted(seen.values(), key=_order)         # top to bottom as on screen, so "the second of two identical rows" is the one lower down


def _finish(session, op, scope_rows, issues_by_key, families_checked, edited_key, grid_rows, today) -> dict:
    changed, to_save = [], []
    for r in scope_rows:
        new = issues_by_key[r.row_key]
        if new != r.issues:
            r.issues = new
            if r.row_key != edited_key:
                changed.append(_verdict(r))
        to_save.append(r)
    GuiRow.objects.bulk_update(to_save, ["normalised", "issues", "employee_code", "relation_code", "updated_at"]) if to_save else None
    everyone = list(GuiRow.objects.filter(session=session, op_type=op).order_by("id"))
    summary = summarise(session, op, [r.normalised for r in everyone], list(range(len(everyone))), {i: r.issues for i, r in enumerate(everyone)},
                        max(grid_rows or 0, len(everyone)), today)
    return {"changed": changed, "summary": summary, "checks": {"family_rules": families_checked, "rules": "same rules as the file path"}}


def put_row(session, op: str, row_key: str, cells: dict, *, position: int = 10**9, grid_rows: int = 0, today: date | None = None, families: bool = True) -> dict:
    """Store this one row and check it, and only the rows it can affect. @return {"row", "changed", "summary", "checks"}"""
    today = today or date.today()
    if _is_blank_row(cells):
        return delete_row(session, op, row_key, grid_rows=grid_rows, today=today, families=families)
    row = GuiRow.objects.filter(session=session, row_key=row_key).first()
    old_code = row.employee_code if row else ""
    old_pan = _pan(row.normalised) if row else None
    clean = {k: v for k, v in cells.items() if k != "row_key" and not k.startswith("_")}
    stored = {**clean, "_pos": position}
    if row is None:
        row = GuiRow(session=session, row_key=row_key, op_type=op, raw=stored, normalised={}, issues=[])
    else:
        row.op_type, row.raw = op, stored
    norm, _ = normalize_row(clean, default_effective_date=session.effective_date.isoformat() if session.effective_date else None, today=today)
    row.normalised, row.employee_code, row.relation_code = norm, norm.get("employee_code") or "", norm.get("relation_code") or ""
    row.save()
    codes = {c for c in (old_code, row.employee_code) if c}
    pans = {p for p in (old_pan, _pan(norm)) if p}
    scope_rows = _scope(session, op, codes, pans, own_pk=row.pk)
    issues, fam = _recheck(session, op, scope_rows, today, families)
    mine = next(r for r in scope_rows if r.pk == row.pk)
    mine.issues = issues[mine.row_key]
    out = _finish(session, op, scope_rows, issues, fam, row_key, grid_rows, today)
    return {"row": _verdict(mine), **out}


def delete_row(session, op: str, row_key: str, *, grid_rows: int = 0, today: date | None = None, families: bool = True) -> dict:
    """Remove a row (or a row that was emptied) and check again the rows that shared its employee or PAN."""
    today = today or date.today()
    row = GuiRow.objects.filter(session=session, row_key=row_key).first()
    codes, pans = set(), set()
    if row:
        if row.employee_code:
            codes.add(row.employee_code)
        if _pan(row.normalised):
            pans.add(_pan(row.normalised))
        row.delete()
    scope_rows = _scope(session, op, codes, pans)
    issues, fam = _recheck(session, op, scope_rows, today, families) if scope_rows else ({}, False)
    out = _finish(session, op, scope_rows, issues, fam, None, grid_rows, today)
    return {"row": {"row_key": row_key, "blank": True, "issues": [], "valid": False}, **out}
