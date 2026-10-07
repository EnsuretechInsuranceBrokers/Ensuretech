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
