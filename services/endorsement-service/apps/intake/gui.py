"""Interactive path: live per-row validation, staging, and the server-side split into typed jobs on submit.

Rules come from apps.validation.rules.issues_scalar (the same rules the bulk path runs, kept in step by a fuzz
test). Family constraints are not asked per keystroke (that would be a network call per edit); they run in the
pipeline right after submit, like for CSV."""
import logging
import datetime as dt

from django.conf import settings
from django.db import transaction
from django.utils import timezone

from apps.core.outbox import run_after_commit
from apps.jobs.machine import transition
from apps.jobs.models import EndorsementJob, EndorsementMember
from apps.jobs.states import State
from apps.validation.models import ChunkState
from apps.validation.rules import REQUIRED, issues_scalar
from apps.validation.tasks import dispatch_validation

from .models import GuiRow, GuiSession, StagedRow
from .normalize import normalize_row, rules
from .parser import row_hash

OPS = ("ADD", "REMOVE", "UPDATE")
LABELS = {"employee_code": "Employee code", "relation": "Relation", "first_name": "First name", "last_name": "Last name",
          "full_name": "Full name", "dob": "Date of birth", "gender": "Gender", "pan": "PAN", "mobile": "Mobile",
          "email": "Email", "grade": "Grade", "designation": "Designation", "annual_salary": "Annual salary",
          "sum_insured": "Sum insured", "effective_date": "Effective date"}
TYPES = {"dob": "date", "effective_date": "date", "annual_salary": "money", "sum_insured": "money"}


def schema() -> dict:
    """Column definitions for the grid. `relation` here is the canonical rule field `relation_code`."""
    def required_for(field):
        rule_field = "relation_code" if field == "relation" else field
        return [op for op in OPS if rule_field in REQUIRED[op]]
    return {"operations": list(OPS),
            "columns": [{"field": f, "label": LABELS[f], "type": TYPES.get(f, "string"), "required_for": required_for(f)}
                        for f in LABELS],
            "relations": rules()["relation_codes"]}


def new_expiry():
    return timezone.now() + dt.timedelta(hours=settings.GUI_SESSION_TTL_HOURS)


def check_row(session: GuiSession, op: str, raw: dict, *, row_key: str | None = None,
              today: dt.date | None = None) -> tuple[dict, list[dict]]:
    """Validate one row against the rules, the live roster and the rest of this session. 2-3 small queries."""
    today = today or dt.date.today()
    default_eff = session.effective_date.isoformat() if session.effective_date else None
    norm, errs = normalize_row(raw, default_effective_date=default_eff, today=today)
    row = {**norm, "_errors": errs}
    found = [(i.field, i.rule, i.severity, i.message) for i in issues_scalar(row, op, today)]

    code, rel = norm.get("employee_code"), norm.get("relation_code")
    if code and rel:
        existing = list(EndorsementMember.objects.filter(policy_id=session.policy_id, is_active=True, employee_code=code))
        exists = any(m.relation_code == rel for m in existing)
        if op == "ADD" and exists:
            found.append(("employee_code", "MEMBER_ALREADY_EXISTS", "ERROR", "member is already covered under this policy"))
        elif op in ("REMOVE", "UPDATE") and not exists:
            found.append(("employee_code", "MEMBER_NOT_FOUND", "ERROR", "no active member with this employee code and relation"))
        clash = GuiRow.objects.filter(session=session, op_type=op, employee_code=code, relation_code=rel)
        if row_key:
            clash = clash.exclude(row_key=row_key)
        if clash.exists():
            found.append(("employee_code", "DUPLICATE_ROW_IN_SESSION", "ERROR", "this member is already staged in this session"))
    pan = norm.get("pan")
    if pan and op in ("ADD", "UPDATE") and code and rel:
        holder = EndorsementMember.objects.filter(policy_id=session.policy_id, is_active=True, pan=pan).first()
        if holder and (holder.employee_code, holder.relation_code) != (code, rel):
            found.append(("pan", "DUPLICATE_PAN_EXISTING", "ERROR", "PAN already belongs to another covered member"))
    return norm, [{"field": f, "rule": r, "severity": s, "message": m} for f, r, s, m in found]


def _chunk_by_employee(rows: list[GuiRow], target: int) -> list[list[GuiRow]]:
    """Keep each employee's rows together (family rules need the whole family); close a chunk at an employee boundary."""
    rows = sorted(rows, key=lambda r: (r.employee_code, r.id))
    out, cur = [], []
    for r in rows:
        if cur and len(cur) >= target and r.employee_code != cur[-1].employee_code:
            out.append(cur)
            cur = []
        cur.append(r)
    if cur:
        out.append(cur)
    return out


def submit(session_id, *, actor: str, actor_ip: str | None) -> tuple[list[dict], bool]:
    """Promote staged rows into one job per operation. @return (jobs, created). Idempotent: a second call returns the
    jobs of the first. Raises ValueError(list_of_row_problems) when staged rows are no longer valid."""
    with transaction.atomic():
        s = GuiSession.objects.select_for_update().get(pk=session_id)
        if s.state == "SUBMITTED":
            return s.submitted_jobs, False
        rows = list(s.rows.order_by("id"))
        if not rows:
            raise ValueError([{"row_key": None, "issues": [{"rule": "NOTHING_TO_SUBMIT", "message": "no rows staged"}]}])

        problems = []
        for r in rows:                       # the roster may have moved since the row was staged: check again now
            norm, issues = check_row(s, r.op_type, r.raw, row_key=r.row_key)
            r.normalised, r.issues = norm, issues
            r.save(update_fields=["normalised", "issues", "updated_at"])
            if any(i["severity"] == "ERROR" for i in issues):
                problems.append({"row_key": r.row_key, "issues": [i for i in issues if i["severity"] == "ERROR"]})
        if problems:
            raise ValueError(problems)

        target = settings.CHUNK_TARGET_ROWS
        jobs = []
        for op in OPS:
            op_rows = [r for r in rows if r.op_type == op]
            if not op_rows:
                continue
            job = EndorsementJob.objects.create(
                broker_id=s.broker_id, employer_id=s.employer_id, policy_id=s.policy_id, source_type="GUI", op_type=op,
                parent_session_id=s.id, effective_date=s.effective_date, actor_user_id=actor, actor_ip=actor_ip,
                metadata={"policy": s.policy_facts, "session_id": str(s.id)})
            logging.getLogger(__name__).info("endorsement job %s created for policy %s (%s); its events carry correlation id %s", job.id, s.policy_id, op, job.correlation_id)
            n = 0
            for ci, chunk in enumerate(_chunk_by_employee(op_rows, target)):
                StagedRow.objects.bulk_create([
                    StagedRow(job_id=job.id, chunk_index=ci, row_index=n + k, source_line=n + k + 1, op_type=op,
                              raw_payload=r.raw, normalised_payload={**r.normalised, "_errors": []},
                              row_hash=row_hash(op, r.normalised)) for k, r in enumerate(chunk)])
                ChunkState.objects.create(job_id=job.id, chunk_index=ci, stage="VALIDATE", row_count=len(chunk))
                n += len(chunk)
            EndorsementJob.objects.filter(pk=job.id).update(total_rows=n, chunk_count=ci + 1)
            transition(job.id, State.FILE_STREAMED, expected_from=State.REQUEST_RAISED, actor=actor, metadata={"rows": n})
            transition(job.id, State.VALIDATION_QUEUED, expected_from=State.FILE_STREAMED, actor=actor)
            jobs.append({"job_id": str(job.id), "op_type": op, "rows": n})
        s.state, s.submitted_at, s.submitted_jobs = "SUBMITTED", timezone.now(), jobs
        s.save()
        for j in jobs:
            run_after_commit(lambda jid=j["job_id"]: dispatch_validation(jid, queue="validation.high"))
        return jobs, True
