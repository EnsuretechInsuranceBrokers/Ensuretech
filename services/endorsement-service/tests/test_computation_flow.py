from datetime import date
from decimal import Decimal

import pytest
from django.db import connection

from apps.computation.policy_client import PolicyRejected, PolicyUnavailable
from apps.jobs.models import AppliedRow, AuditTransition, EndorsementJob, EndorsementMember, PremiumLine

from .conftest import BROKER_A, BROKER_B, EMP_A, add_member, client_for, csv_text, upload
from .fakes import InMemoryCd

pytestmark = pytest.mark.django_db(transaction=True)
BASE = "/api/v1/broker/endorsements/"
D = Decimal

FAMILY = csv_text(
    "1001,Self,Asha Rao,17/05/1985,F,500000,,ABCDE1234F",
    "1001,Spouse,Ravi Rao,02-Feb-1983,M,500000,,",
    "1001,Son,Kid Rao,01/01/2015,M,500000,,",
    "1002,Self,Meera Nair,1990-03-03,F,300000,,PQRST5678K",
)
NOHEAD = "Emp Code,Relationship,Name,DOB,Gender,SI,Effective Date,PAN"


def job_from(resp):
    assert resp.status_code in (200, 202), resp.content
    return EndorsementJob.objects.get(pk=resp.json()["job_id"])


def trail(job):
    return [a.to_state for a in AuditTransition.objects.filter(job=job)]


def events(job, etype):
    with connection.cursor() as cur:
        cur.execute("SELECT payload FROM outbox.outbox_events WHERE aggregate_id=%s AND event_type=%s", [str(job.id), etype])
        import json
        return [json.loads(r[0]) if isinstance(r[0], str) else r[0] for r in cur.fetchall()]   # Django hands jsonb back as text


def test_add_prices_checks_the_deposit_and_writes_the_roster(flow):
    job = job_from(upload(client_for(), FAMILY, effective_date="2030-04-01"))
    assert job.status == "ENDORSED"
    assert trail(job) == ["FILE_STREAMED", "VALIDATION_QUEUED", "VALIDATION_IN_PROGRESS", "VALIDATION_PASSED",
                          "PREMIUM_CALC_QUEUED", "PREMIUM_CALCULATED", "CD_CHECK_INITIATED", "CD_SUFFICIENT",
                          "ENDORSEMENT_QUEUED", "ENDORSEMENT_IN_PROGRESS", "ENDORSED"]
    # flat 1000 per member x 4 members, no pro-rata (policy NONE), 18% tax
    assert (job.premium_delta, job.tax_amount, job.total_premium) == (D("4000.00"), D("720.00"), D("4720.00"))
    assert job.cd_status == "SUFFICIENT"
    members = EndorsementMember.objects.filter(policy_id=100, is_active=True)
    assert members.count() == 4
    asha = members.get(employee_code="1001", relation_code="SELF")
    assert asha.premium_amount == D("1000.00") and asha.first_name == "Asha" and asha.date_of_birth == date(1985, 5, 17)
    assert asha.pan == "ABCDE1234F" and asha.sum_insured == D("500000.00") and asha.broker_id == BROKER_A
    assert AppliedRow.objects.filter(job_id=job.id).count() == 4
    ev = events(job, "EndorsementApplied")
    assert len(ev) == 1 and ev[0]["members_changed"] == 4 and ev[0]["total"] == "4720.00" and ev[0]["policy_id"] == 100


def test_pro_rata_scales_the_charge_by_the_remaining_period(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="DAILY", flat=1000,
                    construct={"allowed": {"SPOUSE", "SON"}, "max_children": 2, "max_members": 5})
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-10-01"))
    factor = (D(182) / D(365)).quantize(D("0.000001"))
    line = PremiumLine.objects.get(job_id=job.id)
    assert line.pro_rata_factor == factor
    assert line.after_annual == D("1000.00") and line.charged == (D("1000") * factor).quantize(D("0.01"))
    assert job.premium_delta == line.charged and job.status == "ENDORSED"
    assert EndorsementMember.objects.get(employee_code="1").premium_amount == D("1000.00")   # annual premium stored, not pro-rated


def test_inception_policy_ignores_pro_rata(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="DAILY", inception=True, flat=1000,
                    construct={"allowed": {"SPOUSE"}, "max_members": 5})
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-10-01"))
    assert job.premium_delta == D("1000.00")


def test_adding_a_dependent_to_an_existing_family_charges_only_the_difference(flow):
    add_member(code="1001", rel="SELF", premium="1000.00")
    job = job_from(upload(client_for(), csv_text("1001,Spouse,Ravi,02-Feb-1983,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "ENDORSED"
    line = PremiumLine.objects.get(job_id=job.id)
    assert (line.before_annual, line.after_annual, line.charged) == (D("1000.00"), D("2000.00"), D("1000.00"))
    assert EndorsementMember.objects.filter(employee_code="1001", is_active=True).count() == 2


def test_remove_refunds_and_needs_no_deposit(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="DAILY", flat=1000, construct={"allowed": {"SPOUSE", "SON"}, "max_children": 2, "max_members": 5})
    add_member(code="1001", rel="SELF")
    add_member(code="1001", rel="SPOUSE")
    InMemoryCd.balance = D("0")                                # an empty deposit must not block a refund
    job = job_from(upload(client_for(), csv_text("1001,Spouse,,,,,,", header=NOHEAD), op="REMOVE", effective_date="2030-06-01"))
    assert job.status == "ENDORSED"
    refund = -(D("1000") * D(303) / D(365)).quantize(D("0.01"))               # cover ran through Jun 1 inclusive; Jun 2 .. Mar 31 = 303 days come back
    assert (job.premium_delta, job.tax_amount, job.total_premium) == (refund, (refund * D("0.18")).quantize(D("0.01")), refund + (refund * D("0.18")).quantize(D("0.01")))
    assert InMemoryCd.calls == []                              # never asked
    assert "skipped" in AuditTransition.objects.get(job=job, to_state="CD_SUFFICIENT").metadata
    gone = EndorsementMember.objects.get(employee_code="1001", relation_code="SPOUSE")
    assert gone.is_active is False and gone.end_date == date(2030, 6, 1)
    assert EndorsementMember.objects.get(employee_code="1001", relation_code="SELF").is_active is True


def test_update_changes_fields_without_touching_the_rest_and_costs_nothing_on_a_flat_policy(flow):
    add_member(code="1001", rel="SELF", si="500000", grade="B")
    job = job_from(upload(client_for(), csv_text("1001,Self,,,,750000,,", header=NOHEAD), op="UPDATE"))
    assert job.status == "ENDORSED" and job.premium_delta == D("0.00")
    m = EndorsementMember.objects.get(employee_code="1001")
    assert m.sum_insured == D("750000.00") and m.grade == "B" and m.first_name == "Old"       # only SI changed


def test_multi_chunk_jobs_aggregate_and_finish_exactly_once(flow, settings):
    settings.CHUNK_TARGET_ROWS = 3
    lines = []
    for e in range(1, 7):
        lines += [f"{e},Self,E{e},1980-01-01,M,1,,", f"{e},Spouse,S{e},1982-01-01,F,1,,", f"{e},Son,K{e},2012-01-01,M,1,,"]
    job = job_from(upload(client_for(), csv_text(*lines), effective_date="2030-04-01"))
    assert job.chunk_count == 6 and job.status == "ENDORSED"
    assert (job.chunks_completed, job.chunks_priced, job.chunks_endorsed) == (6, 6, 6)
    assert job.premium_delta == D("18000.00") and job.total_premium == D("21240.00")      # 18 members x 1000, +18%
    assert EndorsementMember.objects.filter(policy_id=100).count() == 18
    assert AuditTransition.objects.filter(job=job, to_state="ENDORSED").count() == 1
    assert len(events(job, "EndorsementApplied")) == 1


# ── deposit gates ────────────────────────────────────────────────────────────────────────────────────────────
@pytest.mark.parametrize("balance,state", [("0", "CD_INSUFFICIENT"), ("100", "CD_PARTIAL_SUFFICIENT")])
def test_an_inadequate_deposit_blocks_the_endorsement_until_topped_up(flow, balance, state):
    InMemoryCd.balance = D(balance)
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == state and job.cd_status == state.replace("CD_", "")
    assert EndorsementMember.objects.count() == 0                                   # nothing reaches the roster
    assert InMemoryCd.calls[-1][2] == D("1180.00")                                  # asked for premium + tax

    InMemoryCd.balance = D("5000")                                                  # deposit replenished
    r = c.post(f"{BASE}{job.id}/retry/")
    assert r.status_code == 202
    job.refresh_from_db()
    assert job.status == "ENDORSED" and EndorsementMember.objects.count() == 1


def test_default_billing_client_parks_the_job_honestly(world, settings):
    settings.PIPELINE_AUTO_CONTINUE = True
    settings.CD_CLIENT = "apps.computation.cd.UnavailableCdClient"   # the real default: billing not integrated (pinned, not ambient)
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "CD_CHECK_FAILED" and EndorsementMember.objects.count() == 0
    reason = AuditTransition.objects.get(job=job, to_state="CD_CHECK_FAILED").metadata["reason"]
    assert "billing-service is not integrated" in reason


def test_transient_billing_outage_is_retried_without_changing_state(flow):
    calls = {"n": 0}
    orig = InMemoryCd.check

    def flaky(self, *a, **k):
        calls["n"] += 1
        if calls["n"] <= 2:
            from apps.computation.cd import CdUnavailable
            raise CdUnavailable("blip")
        return orig(self, *a, **k)

    InMemoryCd.check = flaky
    try:
        job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    finally:
        InMemoryCd.check = orig
    assert job.status == "ENDORSED" and calls["n"] == 3
    assert trail(job).count("CD_CHECK_INITIATED") == 1 and "CD_CHECK_FAILED" not in trail(job)


def test_persistent_billing_outage_fails_visibly_then_recovers_on_retry(flow):
    InMemoryCd.down = True
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "CD_CHECK_FAILED"
    InMemoryCd.down = False
    assert c.post(f"{BASE}{job.id}/retry/").status_code == 202
    job.refresh_from_db()
    assert job.status == "ENDORSED"


# ── confirmation and retry API ───────────────────────────────────────────────────────────────────────────────
def test_partial_validation_waits_for_confirmation_then_endorses_only_the_valid_rows(flow):
    text = csv_text("1,Self,Good,1990-01-01,M,500000,,", "2,Self,,1990-01-01,M,500000,,", "3,Self,Also Good,1990-01-01,M,500000,,")
    c = client_for()
    job = job_from(upload(c, text, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PARTIAL" and EndorsementMember.objects.count() == 0     # held for a human
    assert client_for("BROKER_NON_ADMIN").post(f"{BASE}{job.id}/proceed/").status_code == 403
    assert client_for("BROKER_ADMIN", BROKER_B).post(f"{BASE}{job.id}/proceed/").status_code == 404
    assert c.post(f"{BASE}{job.id}/proceed/").status_code == 202
    job.refresh_from_db()
    assert job.status == "ENDORSED"
    assert sorted(EndorsementMember.objects.values_list("employee_code", flat=True)) == ["1", "3"]
    assert job.premium_delta == D("2000.00")
    assert c.post(f"{BASE}{job.id}/proceed/").status_code == 409                              # nothing left to confirm


def test_a_clean_job_can_be_held_for_review_when_auto_continue_is_off(world, settings):
    settings.CD_CLIENT = "tests.fakes.InMemoryCd"
    InMemoryCd.reset()
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PASSED"
    assert c.post(f"{BASE}{job.id}/proceed/").status_code == 202
    job.refresh_from_db()
    assert job.status == "ENDORSED"


def test_rate_card_gap_fails_pricing_visibly_and_retry_succeeds_after_the_fix(flow):
    from tests.fakes import FakePolicyClient
    original = FakePolicyClient.rates.pop((BROKER_A, 100))
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "PREMIUM_CALC_FAILED"
    assert "employee 1" in job.error_summary["premium_error"] and "no active rate card" in job.error_summary["premium_error"]
    assert EndorsementMember.objects.count() == 0
    FakePolicyClient.rates[(BROKER_A, 100)] = original
    assert c.post(f"{BASE}{job.id}/retry/").status_code == 202
    job.refresh_from_db()
    assert job.status == "ENDORSED" and PremiumLine.objects.filter(job_id=job.id).count() == 1     # no stale lines doubled


def test_pricing_outage_retries_then_fails_the_job(flow):
    from tests.fakes import FakePolicyClient
    orig = FakePolicyClient.calculate_premium
    FakePolicyClient.calculate_premium = lambda self, *a, **k: (_ for _ in ()).throw(PolicyUnavailable("UNAVAILABLE"))
    try:
        job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    finally:
        FakePolicyClient.calculate_premium = orig
    assert job.status == "PREMIUM_CALC_FAILED" and "unavailable after 6 attempts" in job.error_summary["premium_error"]


def test_nothing_to_retry_is_a_409_and_roles_are_enforced(flow):
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert c.post(f"{BASE}{job.id}/retry/").status_code == 409
    assert client_for("BROKER_NON_ADMIN").post(f"{BASE}{job.id}/retry/").status_code == 403
    assert client_for("BROKER_ADMIN", BROKER_B).post(f"{BASE}{job.id}/retry/").status_code == 404


# ── safety ───────────────────────────────────────────────────────────────────────────────────────────────────
def test_replaying_a_chunk_never_double_applies(flow):
    from apps.computation.apply import apply_chunk
    from apps.computation.tasks import apply_chunk_task
    job = job_from(upload(client_for(), FAMILY, effective_date="2030-04-01"))
    assert apply_chunk_task.apply(args=[str(job.id), 0]).get() == "skipped"                # message redelivered
    assert apply_chunk(job, 0) == 0                                                         # even a forced re-run is a no-op
    assert EndorsementMember.objects.filter(policy_id=100).count() == 4
    assert AuditTransition.objects.filter(job=job, to_state="ENDORSED").count() == 1


def test_a_roster_change_before_pricing_fails_pricing_with_a_clear_reason(world, settings):
    settings.PIPELINE_AUTO_CONTINUE = False
    c = client_for()
    add_member(code="1001", rel="SELF")
    job = job_from(upload(c, csv_text("1001,Self,,,,,,", header=NOHEAD), op="REMOVE", effective_date="2030-06-01"))
    assert job.status == "VALIDATION_PASSED"
    EndorsementMember.objects.filter(employee_code="1001").update(is_active=False)         # someone else removed them first
    c.post(f"{BASE}{job.id}/proceed/")
    job.refresh_from_db()
    assert job.status == "PREMIUM_CALC_FAILED"
    assert "no longer an active member" in job.error_summary["premium_error"]
    assert c.post(f"{BASE}{job.id}/cancel/").json()["status"] == "CANCELLED_BY_HR"          # the way out is a re-upload


def test_a_roster_change_between_pricing_and_endorsement_stops_the_job(flow, monkeypatch):
    import apps.computation.tasks as t
    monkeypatch.setattr(t, "queue_endorsement", lambda job_id: None)                         # freeze after the CD check
    add_member(code="1001", rel="SELF")
    job = job_from(upload(client_for(), csv_text("1001,Self,,,,,,", header=NOHEAD), op="REMOVE", effective_date="2030-06-01"))
    assert job.status == "CD_SUFFICIENT"
    EndorsementMember.objects.filter(employee_code="1001").update(is_active=False)
    monkeypatch.undo()
    t.queue_endorsement(job.id)
    job.refresh_from_db()
    assert job.status == "ENDORSEMENT_FAILED"
    assert "no longer an active member" in AuditTransition.objects.get(job=job, to_state="ENDORSEMENT_FAILED").metadata["reason"]


def test_a_concurrent_add_of_the_same_member_is_caught_by_the_database(flow):
    InMemoryCd.balance = D("0")                                                              # park the job after pricing
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "CD_INSUFFICIENT"
    add_member(code="1", rel="SELF")                                                         # another job got there first
    InMemoryCd.balance = D("5000")
    c.post(f"{BASE}{job.id}/retry/")
    job.refresh_from_db()
    assert job.status == "ENDORSEMENT_FAILED"
    assert EndorsementMember.objects.filter(employee_code="1", is_active=True).count() == 1  # the unique index held


def test_a_failed_endorsement_can_be_retried_once_the_conflict_is_cleared(flow):
    InMemoryCd.balance = D("0")
    c = client_for()
    job = job_from(upload(c, csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    blocker = add_member(code="1", rel="SELF")
    InMemoryCd.balance = D("5000")
    c.post(f"{BASE}{job.id}/retry/")
    job.refresh_from_db()
    assert job.status == "ENDORSEMENT_FAILED"
    blocker.delete()
    assert c.post(f"{BASE}{job.id}/retry/").status_code == 202
    job.refresh_from_db()
    assert job.status == "ENDORSED" and EndorsementMember.objects.filter(employee_code="1", is_active=True).count() == 1


def test_job_totals_and_roster_are_tenant_scoped(flow):
    job = job_from(upload(client_for(), FAMILY, effective_date="2030-04-01"))
    body = client_for().get(f"{BASE}{job.id}/").json()
    assert body["status"] == "ENDORSED" and body["total_premium"] == "4720.00" and body["cd_status"] == "SUFFICIENT"
    assert client_for("BROKER_ADMIN", BROKER_B).get(f"{BASE}{job.id}/").status_code == 404


def test_each_relation_is_pro_rated_by_its_own_kind(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="DAILY", flat=1000, relation_prorata={"SON": "MONTHLY"},
                    construct={"allowed": {"SPOUSE", "SON"}, "max_children": 2, "max_members": 5})
    rows = csv_text("1,Self,Asha,1985-01-01,F,500000,,", "1,Son,Kid,2015-01-01,M,500000,,")
    job = job_from(upload(client_for(), rows, effective_date="2030-10-01"))
    self_part = D("1000") * D(182) / D(365)                                   # daily: Oct 1 .. Mar 31
    son_part = D("1000") * D(6) / D(12)                                       # monthly: ceil(5.97 months) = 6 of 12
    assert job.premium_delta == (self_part + son_part).quantize(D("0.01"))
    line = PremiumLine.objects.get(job_id=job.id)
    assert line.charged == job.premium_delta and D("0.49") < line.pro_rata_factor < D("0.5")      # the blended factor, for the audit trail


def test_a_policy_that_forbids_prorata_charges_joiners_in_full_and_refunds_by_the_configured_fallback(flow, settings):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="NONE", flat=1000, construct={"allowed": {"SPOUSE"}, "max_members": 5})
    assert job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-10-01")).premium_delta == D("1000.00")
    add_member(code="2001", rel="SELF", premium="1000.00")
    refund = lambda code: job_from(upload(client_for(), csv_text(f"{code},Self,,,,,,", header=NOHEAD), op="REMOVE", effective_date="2030-10-01")).premium_delta  # noqa: E731
    assert refund("2001") == -(D("1000") * D(181) / D(365)).quantize(D("0.01"))                 # legacy: daily
    settings.REMOVAL_REFUND_WHEN_PRORATA_DISABLED = "FULL"
    add_member(code="2002", rel="SELF", premium="1000.00")
    assert refund("2002") == D("-1000.00")
    settings.REMOVAL_REFUND_WHEN_PRORATA_DISABLED = "NONE"
    add_member(code="2003", rel="SELF", premium="1000.00")
    assert refund("2003") == D("0.00")


def test_same_day_removal_variant_is_a_setting(flow, settings):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="DAILY", flat=1000, construct={"allowed": {"SPOUSE"}, "max_members": 5})
    m = add_member(code="3001", rel="SELF", premium="1000.00")
    EndorsementMember.objects.filter(pk=m.pk).update(effective_date=date(2030, 10, 1))                # cover began Oct 1
    settings.REMOVAL_INCLUDES_LEAVING_DAY = False
    job = job_from(upload(client_for(), csv_text("3001,Self,,,,,,", header=NOHEAD), op="REMOVE", effective_date="2030-10-01"))
    assert job.premium_delta == -(D("1000") * D(182) / D(365)).quantize(D("0.01"))                    # the leaving day was never covered: refunded too
