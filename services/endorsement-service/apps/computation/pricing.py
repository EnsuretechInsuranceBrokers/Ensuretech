"""Prices the families of one chunk: what the family costs today vs after this endorsement."""
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from apps.intake.models import StagedRow
from apps.jobs.models import EndorsementJob, EndorsementMember
from apps.validation.models import ValidationLog

from .policy_client import PolicyClient, PremiumMember, PremiumResult
from datetime import timedelta

from django.conf import settings

from .contribution import ContributionPlan, group_of
from .pro_rata import joiner_factor, money, removal_factor

UPDATABLE = ("first_name", "last_name", "dob", "gender", "pan", "mobile", "email", "grade", "designation",
             "annual_salary", "sum_insured")


def age_on(dob: date, on: date) -> int:
    return on.year - dob.year - ((on.month, on.day) < (dob.month, dob.day))


def valid_rows(job_id, chunk_index: int) -> list[StagedRow]:
    """Rows that survived validation. A failing row never reaches pricing or the roster."""
    bad = set(ValidationLog.objects.filter(job_id=job_id, chunk_index=chunk_index, severity="ERROR")
              .values_list("row_index", flat=True))
    return [r for r in StagedRow.objects.filter(job_id=job_id, chunk_index=chunk_index).order_by("row_index")
            if r.row_index not in bad]


def _dec(v):
    return None if v in (None, "") else Decimal(str(v))


class RosterMismatch(Exception):
    """A REMOVE/UPDATE row names someone who is not an active member any more (changed since validation)."""


@dataclass
class FamilyLine:
    employee_code: str
    effective_date: date
    before: Decimal
    after: Decimal
    factor: Decimal
    charged: Decimal
    tax_rate: Decimal
    tax: Decimal
    total: Decimal
    member_premiums: list
    employee_charged: Decimal | None = None      # the employee's share of `charged` (None when the policy has no contribution plan)
    employer_charged: Decimal | None = None


def price_chunk(job: EndorsementJob, chunk_index: int, client: PolicyClient) -> list[FamilyLine]:
    facts = job.metadata["policy"]
    start, end = date.fromisoformat(facts["start_date"]), date.fromisoformat(facts["end_date"])
    rows = valid_rows(job.id, chunk_index)
    by_emp: dict[str, list[StagedRow]] = defaultdict(list)
    for r in rows:
        by_emp[r.normalised_payload["employee_code"]].append(r)
    if not by_emp:
        return []

    roster: dict[str, list[EndorsementMember]] = defaultdict(list)
    for m in EndorsementMember.objects.filter(policy_id=job.policy_id, is_active=True, employee_code__in=by_emp):
        roster[m.employee_code].append(m)

    before_req: dict[str, list[PremiumMember]] = {}
    after_req: dict[str, list[PremiumMember]] = {}
    meta: dict[str, tuple[date, list[dict]]] = {}                    # code -> (effective, after-member descriptors)
    cover_start: dict[str, date] = {}                                # member ref -> the day this person's cover began
    for code, new_rows in by_emp.items():
        eff_text = new_rows[0].normalised_payload.get("effective_date")
        eff = date.fromisoformat(eff_text) if eff_text else (job.effective_date or date.today())
        existing = roster.get(code, [])
        if job.op_type in ("REMOVE", "UPDATE"):
            have = {m.relation_code for m in existing}
            missing = [r.normalised_payload["relation_code"] for r in new_rows if r.normalised_payload["relation_code"] not in have]
            if missing:
                raise RosterMismatch(f"employee {code}: {', '.join(missing)} is no longer an active member")

        def pm(ref, rel, dob, grade, desig, si):
            return PremiumMember(ref, rel, age_on(dob, eff), grade, desig, si)

        before = [pm(f"m:{m.id}", m.relation_code, m.date_of_birth, m.grade, m.designation, m.sum_insured) for m in existing]
        for m in existing:
            cover_start[f"m:{m.id}"] = m.effective_date
        desc = [{"relation_code": m.relation_code, "row_index": None, "member_id": m.id} for m in existing]
        after = list(before)
        if job.op_type == "ADD":
            for r in new_rows:
                p = r.normalised_payload
                after.append(pm(f"r:{r.row_index}", p["relation_code"], date.fromisoformat(p["dob"]), p.get("grade"),
                                p.get("designation"), _dec(p.get("sum_insured"))))
                desc.append({"relation_code": p["relation_code"], "row_index": r.row_index, "member_id": None})
                cover_start[f"r:{r.row_index}"] = eff
        elif job.op_type == "REMOVE":
            gone = {r.normalised_payload["relation_code"] for r in new_rows}
            keep = [i for i, m in enumerate(existing) if m.relation_code not in gone]
            after, desc = [after[i] for i in keep], [desc[i] for i in keep]
        elif job.op_type == "UPDATE":
            edits = {r.normalised_payload["relation_code"]: r for r in new_rows}
            after = []
            for m, d in zip(existing, desc):
                r = edits.get(m.relation_code)
                p = r.normalised_payload if r else {}
                dob = date.fromisoformat(p["dob"]) if p.get("dob") else m.date_of_birth
                after.append(pm(f"m:{m.id}", m.relation_code, dob, p.get("grade") or m.grade,
                                p.get("designation") or m.designation, _dec(p.get("sum_insured")) or m.sum_insured))
                if r:
                    d["row_index"] = r.row_index
        if before:
            before_req[code] = before
        if after:
            after_req[code] = after
        meta[code] = (eff, desc)

    before_res: dict[str, PremiumResult] = client.calculate_premiums(job.broker_id, job.policy_id, before_req) if before_req else {}
    after_res: dict[str, PremiumResult] = client.calculate_premiums(job.broker_id, job.policy_id, after_req) if after_req else {}

    kinds = facts.get("relation_prorata") or {}
    default_kind, inception = facts["pro_rata_type"], facts.get("is_inception", False)
    leaving_day = settings.REMOVAL_INCLUDES_LEAVING_DAY
    plan = ContributionPlan(facts.get("contribution_type"), facts.get("contribution_groups"))

    def kind_of(relation_code: str) -> str:
        return kinds.get(relation_code) or default_kind

    lines = []
    for code, (eff, desc) in meta.items():
        b, a = before_res.get(code), after_res.get(code)
        before_amt = b.subtotal if b else Decimal(0)
        after_amt = a.subtotal if a else Decimal(0)
        b_by, a_by = (b.by_ref if b else {}), (a.by_ref if a else {})
        rel_of = {m.ref: m.relation_code for m in before_req.get(code, []) + after_req.get(code, [])}
        raw = Decimal(0)
        raw_employee = Decimal(0)
        b_rels = [m.relation_code for m in before_req.get(code, [])]
        a_rels = [m.relation_code for m in after_req.get(code, [])]
        # Each person's change in premium is pro-rated by that person's relation (pro-rata is configured per relation).
        # A removed person is refunded for the part of the year after their cover ended; everyone else's change starts the next day.
        for ref in set(b_by) | set(a_by):
            delta = a_by.get(ref, Decimal(0)) - b_by.get(ref, Decimal(0))
            e_before = plan.employee_share(group_of(rel_of[ref], b_rels), b_by[ref]) if plan.enabled and ref in b_by else Decimal(0)
            e_after = plan.employee_share(group_of(rel_of[ref], a_rels), a_by[ref]) if plan.enabled and ref in a_by else Decimal(0)
            if delta == 0 and e_after == e_before:       # a member whose premium is unchanged can still change who pays (single -> double parent)
                continue
            kind = kind_of(rel_of[ref])
            if job.op_type == "REMOVE" and ref not in a_by:
                f = removal_factor(kind, start, end, eff, cover_start.get(ref), includes_leaving_day=leaving_day,
                                   when_disabled=settings.REMOVAL_REFUND_WHEN_PRORATA_DISABLED)
            else:
                from_day = eff + timedelta(days=1) if (job.op_type == "REMOVE" and leaving_day) else eff
                f = joiner_factor(kind, start, end, from_day, inception)
            raw += delta * f
            raw_employee += (e_after - e_before) * f
        charged = money(raw)
        employee_charged = employer_charged = None
        if plan.enabled:
            employee_charged = money(raw_employee)
            employer_charged = charged - employee_charged
        total_delta = after_amt - before_amt
        factor = (charged / total_delta).quantize(Decimal("0.000001")) if total_delta else Decimal(1)
        rate = (a or b).tax_rate
        tax = money(charged * rate / 100)
        member_premiums = []
        if a:
            refs = list(after_req[code])
            for d, pmember in zip(desc, refs):
                member_premiums.append({**d, "premium": str(a.by_ref[pmember.ref])})
        lines.append(FamilyLine(code, eff, before_amt, after_amt, factor, charged, rate, tax, charged + tax, member_premiums,
                                employee_charged, employer_charged))
    return lines
