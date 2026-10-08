"""Mid-term dependant rules (legacy is_midterm_enrollement_allowed_for_*, default_midterm_enrollement_days_for_*, midterm_premium_calculation_from_for_*).

A dependant added to an employee who is ALREADY covered joins mid-term. Per member group (SPOUSE, PARTNER, KIDS) the policy says whether that is
allowed, how long after the marriage/birth cover may still be added without HR approval, and where cover starts: the upload day, or the event
(date of marriage for a spouse/partner, date of birth for a child). A group with no rule keeps the sheet's own effective date.

The cover start is written back to the staged row, so validation, pricing and the roster all see the same date. It depends only on the row and the
job, so redelivered validation tasks write the same value.
"""
from datetime import date, timedelta

from apps.jobs.models import EndorsementMember

PARTNERS = {"DOMESTIC_PARTNER"}
SPOUSES = {"SPOUSE", "HUSBAND", "WIFE"}


def group_of(relation_code) -> str | None:
    rc = relation_code or ""
    if rc in SPOUSES:
        return "SPOUSE"
    if rc in PARTNERS:
        return "PARTNER"
    if rc.startswith(("SON", "DAUGHTER")):         # legacy Relation::CHILDRENS is son and daughter only
        return "KIDS"
    return None


def _d(v):
    try:
        return date.fromisoformat(v) if v else None
    except (TypeError, ValueError):
        return None


def apply_rules(job, staged: list, facts: dict, today: date) -> tuple[list, list]:
    """Returns (issues, changed staged rows). issues: (staged index, field, rule, severity, message, value)."""
    rules = facts.get("dependant_midterm") or {}
    if job.op_type != "ADD" or not rules or not facts.get("start_date"):
        return [], []
    candidates = [(i, s.normalised_payload) for i, s in enumerate(staged) if group_of(s.normalised_payload.get("relation_code")) in rules]
    if not candidates:
        return [], []
    codes = {p.get("employee_code") for _, p in candidates if p.get("employee_code")}
    covered = set(EndorsementMember.objects.filter(policy_id=job.policy_id, is_active=True, relation_code="SELF", employee_code__in=codes)
                  .values_list("employee_code", flat=True))
    policy_start, upload_day = date.fromisoformat(facts["start_date"]), job.created_at.date()
    issues, changed = [], []
    for i, p in candidates:
        if p.get("employee_code") not in covered:
            continue                                            # the employee joins in this very file: not a mid-term dependant
        g = group_of(p["relation_code"])
        rule = rules[g]
        if not rule.get("allowed", True):
            issues.append((i, "relation_code", "MIDTERM_DEPENDANT_NOT_ALLOWED", "ERROR",
                           f"this policy does not allow a {g.lower()} to be added after the employee is covered", p["relation_code"]))
            continue
        event_field = "dob" if g == "KIDS" else "date_of_marriage"
        event = _d(p.get(event_field))
        if rule.get("start_basis") == "EVENT":
            if event is None:
                issues.append((i, event_field, "MIDTERM_EVENT_DATE_REQUIRED", "ERROR",
                               "cover starts from the " + ("date of birth" if g == "KIDS" else "date of marriage") + ", which is missing", ""))
                continue
            if event > today:
                issues.append((i, event_field, "MIDTERM_EVENT_IN_FUTURE", "ERROR", "the date of " + ("birth" if g == "KIDS" else "marriage") + " is in the future", event.isoformat()))
                continue
            start = max(event, policy_start)
        else:
            start = max(upload_day, policy_start)
        window = rule.get("window_days")
        if window is not None and event is not None and (today - event) > timedelta(days=window):
            issues.append((i, event_field, "MIDTERM_APPROVAL_REQUIRED", "WARN",
                           f"added more than {window} days after the " + ("birth" if g == "KIDS" else "marriage") + ": HR approval is required for mid-term cover", event.isoformat()))
        if p.get("effective_date") != start.isoformat():
            staged[i].normalised_payload = {**p, "effective_date": start.isoformat()}
            changed.append(staged[i])
    return issues, changed
