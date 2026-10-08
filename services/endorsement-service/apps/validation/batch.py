"""Chunk-level validation: row rules + set-based rules (duplicates, roster state, family constraints).

One DB query for the roster and one RPC for all families per chunk: no per-row round trips.
"""
from dataclasses import dataclass
from datetime import date

from apps.computation.policy_client import Person, PolicyClient
from apps.jobs.models import EndorsementJob, EndorsementMember

from .rules import Issue, issues_vectorized, to_frame


@dataclass(frozen=True)
class RowIssue:
    row_index: int           # job-wide row index (StagedRow.row_index)
    source_line: int
    employee_code: str
    field: str
    rule: str
    severity: str
    message: str
    value: str = ""


def _age(dob: date, on: date) -> int:
    return on.year - dob.year - ((on.month, on.day) < (dob.month, dob.day))


def validate_rows(job: EndorsementJob, staged: list, policy_facts: dict, client: PolicyClient,
                  today: date | None = None) -> list[RowIssue]:
    """staged: StagedRow instances of ONE chunk, ordered by row_index."""
    today = today or date.today()
    if not staged:
        return []
    op = job.op_type
    payloads = [s.normalised_payload for s in staged]
    df = to_frame(payloads)
    found: list[tuple[int, Issue]] = [(i.row_index, i) for i in issues_vectorized(df, op, today)]

    def add(i: int, field, rule, msg, sev="ERROR", value=""):
        found.append((i, Issue(i, field, rule, sev, msg, str(value) if value is not None else "")))

    key = df["employee_code"].astype(str) + "|" + df["relation_code"].astype(str)
    has_key = df["employee_code"].notna() & df["relation_code"].notna()

    # duplicates inside the chunk (families are never split across chunks, so this is the whole file's view)
    for i in df.index[has_key & key.duplicated(keep="first")]:
        add(int(i), "employee_code", "DUPLICATE_ROW_IN_FILE", "same employee and relation appears more than once", value=key[i])
    # NO_MIDTERM: once the policy has started (and is out of its inception window) nobody new can be added
    if op == "ADD" and policy_facts.get("midterm_basis") == "NO_MIDTERM" and not policy_facts.get("is_inception") and policy_facts.get("start_date"):
        start = policy_facts["start_date"]
        for i in df.index[df["effective_date"].notna() & df["effective_date"].astype(str).gt(start)]:      # ISO dates compare as text
            add(int(i), "effective_date", "MIDTERM_NOT_ALLOWED", f"this policy does not accept mid-term joiners (it started on {start})", value=df.at[i, "effective_date"])
    pan = df["pan"].where(df["pan"].notna() & df["pan"].astype(str).str.strip().ne(""))
    first_key = key.groupby(pan).transform("first")
    for i in df.index[pan.notna() & key.ne(first_key)]:
        add(int(i), "pan", "DUPLICATE_PAN_IN_FILE", "this PAN is used by another person in the file", value=pan[i])

    # roster state (single query for the whole chunk)
    codes = {c for c in df["employee_code"].dropna().astype(str)}
    roster = list(EndorsementMember.objects.filter(policy_id=job.policy_id, is_active=True, employee_code__in=codes))
    by_key = {(m.employee_code, m.relation_code): m for m in roster}
    for i in df.index[has_key]:
        k = (df.at[i, "employee_code"], df.at[i, "relation_code"])
        exists = k in by_key
        if op == "ADD" and exists:
            add(int(i), "employee_code", "MEMBER_ALREADY_EXISTS", "member is already covered under this policy", value=k[0])
        elif op in ("REMOVE", "UPDATE") and not exists:
            add(int(i), "employee_code", "MEMBER_NOT_FOUND", "no active member with this employee code and relation", value=k[0])

    pans = {p for p in pan.dropna().astype(str)}
    if pans and op in ("ADD", "UPDATE"):
        holders = {m.pan: m for m in EndorsementMember.objects.filter(policy_id=job.policy_id, is_active=True,
                                                                      pan__in=pans)}
        for i in df.index[pan.notna() & has_key]:
            m = holders.get(pan[i])
            if m and (m.employee_code, m.relation_code) != (df.at[i, "employee_code"], df.at[i, "relation_code"]):
                add(int(i), "pan", "DUPLICATE_PAN_EXISTING", "PAN already belongs to another covered member", value=pan[i])

    if op == "REMOVE":                                      # removing an employee who still has dependents
        removing = {(df.at[i, "employee_code"], df.at[i, "relation_code"]) for i in df.index[has_key]}
        for i in df.index[has_key & df["relation_code"].eq("SELF")]:
            code = df.at[i, "employee_code"]
            left = [m for m in roster if m.employee_code == code and m.relation_code != "SELF"
                    and (code, m.relation_code) not in removing]
            if left:
                add(int(i), "relation_code", "DEPENDENTS_REMAIN_ACTIVE",
                    f"{len(left)} dependent(s) stay covered after the employee is removed", "WARN", code)

    # family constraints, asked of policy-service for every employee whose own rows are otherwise clean
    if op == "ADD" and policy_facts.get("requires_family_construct"):
        bad_rows = {r for r, iss in found if iss.severity == "ERROR"}
        families: dict[str, list[Person]] = {}
        owners: dict[str, list[int | None]] = {}
        order: dict[str, list[int]] = {}
        for i in df.index[has_key]:
            order.setdefault(df.at[i, "employee_code"], []).append(int(i))
        for code, idxs in order.items():
            if any(i in bad_rows for i in idxs):
                continue                                    # row-level problems already reported; avoid noise
            ref = date.fromisoformat(df.at[idxs[0], "effective_date"])
            people = [Person(m.relation_code, _age(m.date_of_birth, ref), m.date_of_birth.isoformat()) for m in roster if m.employee_code == code]
            own = [None] * len(people)
            for i in idxs:
                people.append(Person(df.at[i, "relation_code"], _age(date.fromisoformat(df.at[i, "dob"]),
                                                                    date.fromisoformat(df.at[i, "effective_date"])),
                                     df.at[i, "dob"]))
                own.append(i)
            # policy-service wants the employee first; roster members already include SELF when it exists
            families[code], owners[code] = people, own
        if families:
            results = client.validate_families(job.broker_id, job.policy_id, families)
            for code, issues in results.items():
                for iss in issues:
                    idx = owners[code][iss.member_index] if 0 <= iss.member_index < len(owners[code]) else None
                    target = idx if idx is not None else order[code][0]
                    add(target, "", iss.code, iss.message)

    out = []
    for i, iss in found:
        s = staged[i]
        out.append(RowIssue(s.row_index, s.source_line, str(df.at[i, "employee_code"] or ""), iss.field, iss.rule,
                            iss.severity, iss.message, iss.value))
    return out


def failing_rows(issues: list[RowIssue]) -> set[int]:
    return {i.row_index for i in issues if i.severity == "ERROR"}

