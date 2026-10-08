import datetime as dt

from apps.jobs.models import EndorsementMember

from .conftest import client_for

BASE = "/api/v1/internal/endorsements/"


def roster(job, n, **kw):
    return [EndorsementMember.objects.create(
        job=job, policy_id=job.policy_id, broker_id=job.broker_id, employer_id=job.employer_id, employee_code=f"E{i}",
        relation_code="SELF", first_name=f"N{i}", date_of_birth=dt.date(1990, 1, 1), sum_insured=500000,
        effective_date=dt.date(2030, 4, 1), source_type="CSV_UPLOAD", **kw) for i in range(n)]


def test_only_system_may_use_internal_api(make_job):
    job = make_job()
    for role, bid in (("BROKER_ADMIN", 1), ("EMPLOYER_ADMIN", 1), ("TPA_USER", None)):
        c = client_for(role, bid, 10 if role == "EMPLOYER_ADMIN" else None)
        assert c.get(f"{BASE}{job.id}/members/").status_code == 403
        assert c.post(f"{BASE}{job.id}/tpa-member-ids/", {"members": [{"id": 1, "tpa_member_id": "x"}]}, format="json").status_code == 403
    from rest_framework.test import APIClient
    assert APIClient().get(f"{BASE}{job.id}/members/").status_code == 401


def test_members_are_keyset_paged_and_only_this_jobs(make_job):
    job, other = make_job(), make_job(policy_id=101)
    rows = roster(job, 5)
    roster(other, 2)
    c = client_for("SYSTEM", None)
    first = c.get(f"{BASE}{job.id}/members/?limit=2").json()
    assert [m["id"] for m in first["members"]] == [r.id for r in rows[:2]] and first["next_after"] == rows[1].id
    seen = [m["id"] for m in first["members"]]
    after = first["next_after"]
    while after:
        page = c.get(f"{BASE}{job.id}/members/?limit=2&after={after}").json()
        seen += [m["id"] for m in page["members"]]
        after = page["next_after"]
    assert seen == [r.id for r in rows]
    assert first["policy_id"] == job.policy_id and "pan" not in first["members"][0]       # PAN/salary are not shared
    assert c.get(f"{BASE}{job.id}/members/?limit=abc").status_code == 400
    assert c.get(f"{BASE}00000000-0000-4000-8000-000000000000/members/").status_code == 404


def test_tpa_ids_written_back_only_within_the_jobs_policy(make_job):
    job, other = make_job(), make_job(policy_id=101)
    mine, foreign = roster(job, 2), roster(other, 1)
    c = client_for("SYSTEM", None)
    r = c.post(f"{BASE}{job.id}/tpa-member-ids/", {"members": [{"id": mine[0].id, "tpa_member_id": "T-1"},
                                                                {"id": foreign[0].id, "tpa_member_id": "EVIL"}]}, format="json")
    assert r.json() == {"updated": 1, "ignored": 1}
    assert EndorsementMember.objects.get(pk=mine[0].id).tpa_member_id == "T-1"
    assert EndorsementMember.objects.get(pk=foreign[0].id).tpa_member_id is None
    for bad in ({"members": []}, {"members": [{"id": "x", "tpa_member_id": "a"}]}, {"members": [{"id": 1, "tpa_member_id": " "}]}, {}):
        assert c.post(f"{BASE}{job.id}/tpa-member-ids/", bad, format="json").status_code == 400


def _walk(job, *states):
    from apps.jobs.machine import transition
    from apps.jobs.states import State
    for s in states:
        job = transition(job.id, State[s], actor="t")
    return job


HAPPY = ["FILE_STREAMED", "VALIDATION_QUEUED", "VALIDATION_IN_PROGRESS", "VALIDATION_PASSED", "PREMIUM_CALC_QUEUED", "PREMIUM_CALCULATED",
         "CD_CHECK_INITIATED", "CD_SUFFICIENT", "ENDORSEMENT_QUEUED", "ENDORSEMENT_IN_PROGRESS", "ENDORSED"]


def test_lifecycle_drives_the_post_endorsement_states_and_audits_the_reference(make_job):
    from apps.jobs.models import AuditTransition
    job = _walk(make_job(), *HAPPY)
    c = client_for("SYSTEM", None)
    for state in ("SENT_TO_INSURER", "AWAITING_TPA_CONFIRMATION", "TPA_CONFIRMATION_RECEIVED", "AWAITING_ENDORSEMENT_COPY",
                  "ECARDS_AWAITED_FROM_TPA", "COMPLETED"):
        r = c.post(f"{BASE}{job.id}/lifecycle/", {"state": state, "reference": "INS-77"}, format="json")
        assert r.status_code == 200 and r.json()["state"] == state, r.content
    assert r.json()["is_terminal"] is True
    row = AuditTransition.objects.get(job=job, to_state="SENT_TO_INSURER")
    assert row.actor == "integration-service" and row.metadata == {"reference": "INS-77"}


def test_lifecycle_rejects_illegal_moves_unknown_states_and_states_the_engine_owns(make_job):
    job = _walk(make_job(), *HAPPY)
    c = client_for("SYSTEM", None)
    assert c.post(f"{BASE}{job.id}/lifecycle/", {"state": "ECARDS_AWAITED_FROM_TPA"}, format="json").status_code == 409      # not from ENDORSED
    assert c.post(f"{BASE}{job.id}/lifecycle/", {"state": "TPA_CONFIRMATION_RECEIVED"}, format="json").status_code == 409
    for bad in ("NOPE", "", "VALIDATION_PASSED", "CANCELLED_BY_HR", "ENDORSED"):
        assert c.post(f"{BASE}{job.id}/lifecycle/", {"state": bad}, format="json").status_code == 400, bad
    assert c.post(f"{BASE}00000000-0000-4000-8000-000000000000/lifecycle/", {"state": "COMPLETED"}, format="json").status_code == 404
    assert client_for("BROKER_ADMIN", 1).post(f"{BASE}{job.id}/lifecycle/", {"state": "COMPLETED"}, format="json").status_code == 403


def test_insurer_report_discrepancy_loops_back_through_reupload(make_job):
    job = _walk(make_job(), *HAPPY, "SENT_TO_INSURER", "IC_REPORT_APPROVAL_PENDING", "IC_REPORT_DISCREPANCY_RAISED")
    c = client_for("SYSTEM", None)
    assert c.post(f"{BASE}{job.id}/lifecycle/", {"state": "COMPLETED"}, format="json").status_code == 409     # must be fixed first
    for s in ("REUPLOAD_IN_PROGRESS", "SENT_TO_INSURER", "COMPLETED"):
        assert c.post(f"{BASE}{job.id}/lifecycle/", {"state": s}, format="json").status_code == 200


def test_policy_roster_and_tpa_ids_are_system_only_active_only_and_policy_scoped(make_job):
    job, other = make_job(), make_job(policy_id=101)
    gone = roster(job, 1)[0]
    type(gone).objects.filter(pk=gone.pk).update(is_active=False)
    mine = roster(job, 3)
    gone = None
    foreign = roster(other, 1)
    c = client_for("SYSTEM", None)
    page = c.get(f"/api/v1/internal/policies/{job.policy_id}/members/?limit=2").json()
    assert [m["id"] for m in page["members"]] == [mine[0].id, mine[1].id] and page["next_after"] == mine[1].id
    rest = c.get(f"/api/v1/internal/policies/{job.policy_id}/members/?after={page['next_after']}").json()
    assert [m["id"] for m in rest["members"]] == [mine[2].id] and rest["next_after"] is None        # the inactive one is not listed
    assert page["broker_id"] == job.broker_id and "policy_number" in page
    r = c.post(f"/api/v1/internal/policies/{job.policy_id}/tpa-member-ids/",
               {"members": [{"id": mine[0].id, "tpa_member_id": "T-9"}, {"id": foreign[0].id, "tpa_member_id": "X"}]}, format="json").json()
    assert r == {"updated": 1, "ignored": 1}
    mine[0].refresh_from_db(); foreign[0].refresh_from_db()
    assert mine[0].tpa_member_id == "T-9" and not foreign[0].tpa_member_id
    assert c.get(f"/api/v1/internal/policies/{job.policy_id}/members/?limit=x").status_code == 400
    for role, bid in (("BROKER_ADMIN", 1), ("TPA_USER", None)):
        assert client_for(role, bid).get(f"/api/v1/internal/policies/{job.policy_id}/members/").status_code == 403


def test_a_job_with_rejected_rows_finishes_as_partial_processed(make_job):
    from apps.jobs.models import EndorsementJob
    job = _walk(make_job(), *HAPPY)
    EndorsementJob.objects.filter(pk=job.id).update(failed_rows=3)
    r = client_for("SYSTEM", None).post(f"{BASE}{job.id}/lifecycle/", {"state": "COMPLETED"}, format="json").json()
    assert r["state"] == "PARTIAL_PROCESSED" and r["is_terminal"] is True and r["category"] == "PARTIAL"


def test_an_operator_can_close_an_applied_endorsement_but_not_one_still_being_processed(make_job):
    waiting = _walk(make_job(), *HAPPY, "SENT_TO_INSURER", "AWAITING_TPA_CONFIRMATION")
    broker = client_for("BROKER_ADMIN", 1)
    r = broker.post(f"/api/v1/broker/endorsements/{waiting.id}/close/", {"reason": "insurer confirmed by phone"}, format="json")
    assert r.status_code == 200 and r.json()["status"] == "CLOSED"
    from apps.jobs.models import AuditTransition
    assert AuditTransition.objects.get(job=waiting, to_state="CLOSED").metadata == {"reason": "insurer confirmed by phone"}
    assert broker.post(f"/api/v1/broker/endorsements/{waiting.id}/close/", {}, format="json").status_code == 200        # closing twice is a no-op
    done = _walk(make_job(), *HAPPY, "COMPLETED")
    assert broker.post(f"/api/v1/broker/endorsements/{done.id}/close/", {}, format="json").status_code == 409          # already finished another way
    early = _walk(make_job(), "FILE_STREAMED", "VALIDATION_QUEUED")
    assert broker.post(f"/api/v1/broker/endorsements/{early.id}/close/", {}, format="json").status_code == 409          # not applied yet
    assert client_for("BROKER_ADMIN", 2).post(f"/api/v1/broker/endorsements/{waiting.id}/close/", {}, format="json").status_code == 404
