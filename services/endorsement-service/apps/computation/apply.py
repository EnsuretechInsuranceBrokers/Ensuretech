"""Writes one chunk's endorsement onto the live roster. One transaction per chunk, guarded by AppliedRow markers,
so a retry after a crash neither loses nor duplicates anything."""
from datetime import date

from django.db import transaction

from apps.jobs.models import AppliedRow, EndorsementJob, EndorsementMember, PremiumLine

from .pricing import UPDATABLE, _dec, valid_rows


class ConsistencyError(Exception):
    """The roster no longer matches what validation saw (another job changed it in between)."""


def apply_chunk(job: EndorsementJob, chunk_index: int) -> int:
    rows = valid_rows(job.id, chunk_index)
    lines = {l.employee_code: l for l in PremiumLine.objects.filter(job_id=job.id, chunk_index=chunk_index)}
    premium_for_row: dict[int, str] = {}
    for line in lines.values():
        for mp in line.member_premiums:
            if mp["row_index"] is not None:
                premium_for_row[mp["row_index"]] = mp["premium"]
    applied = 0
    with transaction.atomic():
        done = set(AppliedRow.objects.filter(job_id=job.id, chunk_index=chunk_index).values_list("row_index", flat=True))
        for r in rows:
            if r.row_index in done:
                continue
            p = r.normalised_payload
            key = dict(policy_id=job.policy_id, employee_code=p["employee_code"], relation_code=p["relation_code"],
                       is_active=True)
            if job.op_type == "ADD":
                EndorsementMember.objects.create(
                    job=job, policy_id=job.policy_id, broker_id=job.broker_id, employer_id=job.employer_id,
                    employee_code=p["employee_code"], relation_code=p["relation_code"], first_name=p["first_name"],
                    last_name=p.get("last_name") or "", date_of_birth=p["dob"], gender=p.get("gender"), pan=p.get("pan"),
                    mobile=p.get("mobile"), email=p.get("email"), grade=p.get("grade"), designation=p.get("designation"),
                    annual_salary=_dec(p.get("annual_salary")), sum_insured=_dec(p["sum_insured"]),
                    premium_amount=_dec(premium_for_row.get(r.row_index)), effective_date=p["effective_date"],
                    source_type=job.source_type)
            else:
                m = EndorsementMember.objects.select_for_update().filter(**key).first()
                if m is None:
                    raise ConsistencyError(f"{p['employee_code']}/{p['relation_code']} is no longer an active member")
                if job.op_type == "REMOVE":
                    m.is_active, m.end_date, m.job = False, date.fromisoformat(p["effective_date"]), job
                else:
                    for f in UPDATABLE:
                        if p.get(f) not in (None, ""):
                            col = "date_of_birth" if f == "dob" else f
                            setattr(m, col, _dec(p[f]) if f in ("annual_salary", "sum_insured") else p[f])
                    m.job = job
                m.save()
            AppliedRow.objects.create(job_id=job.id, chunk_index=chunk_index, row_index=r.row_index)
            applied += 1
        # members whose own premium moved because the family's composition changed (e.g. a floater charge on SELF)
        for line in lines.values():
            for mp in line.member_premiums:
                if mp["member_id"] is not None:
                    EndorsementMember.objects.filter(pk=mp["member_id"]).update(premium_amount=mp["premium"])
    return applied
