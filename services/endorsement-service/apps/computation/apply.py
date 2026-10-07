"""Writes one chunk's endorsement onto the live roster. One transaction per chunk, guarded by AppliedRow markers,
so a retry after a crash neither loses nor duplicates anything. Bulk operations throughout: a chunk is a handful of
statements, not two per row."""
from datetime import date

from django.db import transaction
from django.utils import timezone

from apps.jobs.models import AppliedRow, EndorsementJob, EndorsementMember, PremiumLine

from .pricing import UPDATABLE, _dec, valid_rows


class ConsistencyError(Exception):
    """The roster no longer matches what validation saw (another job changed it in between)."""


def apply_chunk(job: EndorsementJob, chunk_index: int) -> int:
    rows = valid_rows(job.id, chunk_index)
    lines = list(PremiumLine.objects.filter(job_id=job.id, chunk_index=chunk_index))
    premium_for_row: dict[int, str] = {}
    moved: dict[int, str] = {}                      # existing member id -> new annual premium (family composition changed)
    for line in lines:
        for mp in line.member_premiums:
            if mp["row_index"] is not None:
                premium_for_row[mp["row_index"]] = mp["premium"]
            if mp["member_id"] is not None:
                moved[mp["member_id"]] = mp["premium"]

    now = timezone.now()
    with transaction.atomic():
        done = set(AppliedRow.objects.filter(job_id=job.id, chunk_index=chunk_index).values_list("row_index", flat=True))
        todo = [r for r in rows if r.row_index not in done]

        if job.op_type == "ADD":
            EndorsementMember.objects.bulk_create([EndorsementMember(
                job=job, policy_id=job.policy_id, broker_id=job.broker_id, employer_id=job.employer_id,
                employee_code=p["employee_code"], relation_code=p["relation_code"], first_name=p["first_name"],
                last_name=p.get("last_name") or "", date_of_birth=p["dob"], gender=p.get("gender"), pan=p.get("pan"),
                mobile=p.get("mobile"), email=p.get("email"), grade=p.get("grade"), designation=p.get("designation"),
                annual_salary=_dec(p.get("annual_salary")), sum_insured=_dec(p["sum_insured"]),
                premium_amount=_dec(premium_for_row.get(r.row_index)), effective_date=p["effective_date"],
                source_type=job.source_type) for r in todo for p in [r.normalised_payload]], batch_size=1000)
        elif todo:
            codes = {r.normalised_payload["employee_code"] for r in todo}
            live = {(m.employee_code, m.relation_code): m for m in EndorsementMember.objects.select_for_update()
                    .filter(policy_id=job.policy_id, is_active=True, employee_code__in=codes)}
            changed, fields = [], {"job", "updated_at"}
            for r in todo:
                p = r.normalised_payload
                m = live.get((p["employee_code"], p["relation_code"]))
                if m is None:
                    raise ConsistencyError(f"{p['employee_code']}/{p['relation_code']} is no longer an active member")
                if job.op_type == "REMOVE":
                    m.is_active, m.end_date = False, date.fromisoformat(p["effective_date"])
                    fields |= {"is_active", "end_date"}
                else:
                    for f in UPDATABLE:
                        if p.get(f) not in (None, ""):
                            col = "date_of_birth" if f == "dob" else f
                            setattr(m, col, _dec(p[f]) if f in ("annual_salary", "sum_insured") else p[f])
                            fields.add(col)
                m.job, m.updated_at = job, now
                changed.append(m)
            EndorsementMember.objects.bulk_update(changed, sorted(fields), batch_size=1000)

        AppliedRow.objects.bulk_create([AppliedRow(job_id=job.id, chunk_index=chunk_index, row_index=r.row_index)
                                        for r in todo], batch_size=1000)
        if moved:                                   # e.g. a floater's family charge sits on SELF and just changed
            members = list(EndorsementMember.objects.filter(pk__in=moved))
            for m in members:
                m.premium_amount, m.updated_at = moved[m.pk], now
            EndorsementMember.objects.bulk_update(members, ["premium_amount", "updated_at"], batch_size=1000)
    return len(todo)
