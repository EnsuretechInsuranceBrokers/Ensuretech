import pytest

from apps.core import mongo
from apps.core.auth import Principal
from apps.projections.handlers import handle_event

from .conftest import BROKER_A, BROKER_B, EMP_A, EMP_A2, EMP_B, client_for, sample

BASE = "/api/v1/broker/read/"
EBASE = "/api/v1/employer/read/"


def job_event(job, broker=BROKER_A, employer=EMP_A, policy=1, state="ENDORSED", ts="2030-10-01T10:00:00+00:00", terminal=True):
    e = sample("endorsement-state-changed")
    e.update(event_id=f"ev-{job}-{state}", job_id=job, broker_id=broker, employer_id=employer, policy_id=policy, to_state=state, timestamp=ts,
             is_terminal=terminal, category="DONE" if terminal else "ACTIVE")
    return e


@pytest.fixture
def world(db):
    handle_event(db, job_event("job-a1"))
    handle_event(db, job_event("job-a2", employer=EMP_A2, state="CD_INSUFFICIENT", terminal=False, policy=2))
    handle_event(db, job_event("job-b1", broker=BROKER_B, employer=EMP_B, policy=9))
    for pid, b, e in ((1, BROKER_A, EMP_A), (2, BROKER_A, EMP_A2), (9, BROKER_B, EMP_B)):
        handle_event(db, {**sample("policy-approved"), "policy_id": pid, "broker_id": b, "employer_id": e})
        handle_event(db, {**sample("cd-balance-updated"), "policy_id": pid, "broker_id": b, "employer_id": e})
    return db


def ids(r):
    return sorted(x["id"] for x in r.json()["results"])


def test_brokers_see_only_their_own_data(world):
    assert ids(client_for("BROKER_ADMIN", BROKER_A).get(BASE + "jobs/")) == ["job-a1", "job-a2"]
    assert ids(client_for("BROKER_ADMIN", BROKER_B).get(BASE + "jobs/")) == ["job-b1"]
    a = client_for("BROKER_NON_ADMIN", BROKER_A)
    assert a.get(BASE + "jobs/job-b1/").status_code == 404 and a.get(BASE + "policies/9/").status_code == 404
    assert ids(a.get(BASE + "policies/")) == [1, 2] or sorted(x["id"] for x in a.get(BASE + "policies/").json()["results"]) == [1, 2]
    assert a.get(BASE + "cd/").json()["count"] == 2


def test_employer_users_are_pinned_to_their_employer(world):
    hr = client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A)
    assert ids(hr.get(EBASE + "jobs/")) == ["job-a1"]
    assert hr.get(EBASE + "jobs/job-a2/").status_code == 404                           # same broker, other employer
    assert hr.get(EBASE + "policies/2/").status_code == 404 and hr.get(EBASE + "policies/1/").status_code == 200
    assert hr.get(EBASE + "dashboard/").json()["jobs"]["total"] == 1


def test_gateways_and_roles_fail_closed(world):
    assert client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A).get(BASE + "jobs/").status_code == 403
    assert client_for("BROKER_ADMIN", BROKER_A).get(EBASE + "jobs/").status_code == 403
    assert client_for("SYSTEM", None).get(BASE + "jobs/").status_code == 403
    assert client_for("TPA_USER", None).get(BASE + "jobs/").status_code == 403
    assert client_for("BROKER_ADMIN", None).get(BASE + "jobs/").status_code == 403           # broker role without a broker id
    assert client_for("EMPLOYER_ADMIN", BROKER_A, None).get(EBASE + "jobs/").status_code == 403
    from rest_framework.test import APIClient
    assert APIClient().get(BASE + "jobs/").status_code == 401


def test_job_detail_carries_its_history_in_time_order(world, db):
    handle_event(db, job_event("job-a1", state="CD_SUFFICIENT", ts="2030-10-01T09:00:00+00:00", terminal=False))
    d = client_for("BROKER_ADMIN", BROKER_A).get(BASE + "jobs/job-a1/").json()
    assert [h["to_state"] for h in d["history"]] == ["CD_SUFFICIENT", "ENDORSED"] and d["state"] == "ENDORSED"


def test_policy_detail_joins_cd_and_syncs(world, db):
    handle_event(db, {**sample("tpa-sync-failed"), "policy_id": 1})
    d = client_for("BROKER_ADMIN", BROKER_A).get(BASE + "policies/1/").json()
    assert d["cd_account"]["low"] is True and d["recent_tpa_syncs"][0]["status"] == "FAILED"


def test_dashboard_is_computed_per_tenant(world):
    d = client_for("BROKER_ADMIN", BROKER_A).get(BASE + "dashboard/").json()
    assert d["jobs"] == {"total": 2, "open": 1, "by_state": {"ENDORSED": 1, "CD_INSUFFICIENT": 1}}
    assert d["cd"] == {"accounts": 2, "low": 2}


def test_filters_are_whitelisted_typed_and_not_injectable(world):
    c = client_for("BROKER_ADMIN", BROKER_A)
    assert ids(c.get(BASE + "jobs/?state=ENDORSED")) == ["job-a1"]
    assert ids(c.get(BASE + "jobs/?is_terminal=false")) == ["job-a2"]
    assert c.get(BASE + "jobs/?policy_id=abc").status_code == 400 and c.get(BASE + "jobs/?is_terminal=maybe").status_code == 400
    assert c.get(BASE + "jobs/?page=x").status_code == 400
    assert c.get(BASE + "jobs/?state[$ne]=ENDORSED").json()["count"] == 2              # operator-looking keys are just unknown params
    assert c.get(BASE + "jobs/?broker_id=2").json()["count"] == 2                      # cannot widen to another tenant
    assert c.get(BASE + "jobs/?state=" + "%7B%22%24ne%22%3A1%7D").json()["count"] == 0
    assert c.get(BASE + "jobs/?page_size=100000").json()["page_size"] == 200


# ── the scoping layer itself ────────────────────────────────────────────────────────────────────────────────
def p(role, broker, employer=None):
    return Principal("u", role, broker, employer, None)


def test_scoped_collection_merges_the_tenant_filter_into_every_operation(world):
    jobs = mongo.scoped(p("BROKER_ADMIN", BROKER_A), mongo.JOBS)
    assert jobs.count() == 2
    assert jobs.find_one({"_id": "job-b1"}) is None
    assert jobs.find({"$or": [{"broker_id": BROKER_B}, {"state": "ENDORSED"}]}).to_list() and all(j["broker_id"] == BROKER_A for j in jobs.find({"$or": [{"broker_id": BROKER_B}, {}]}))
    assert [r["n"] for r in jobs.aggregate([{"$group": {"_id": None, "n": {"$sum": 1}}}])] == [2]


@pytest.mark.parametrize("stage", [{"$lookup": {"from": "jobs", "localField": "a", "foreignField": "b", "as": "x"}}, {"$out": "stolen"},
                                   {"$merge": {"into": "x"}}, {"$unionWith": "jobs"}, {"$facet": {"a": []}}, {"$graphLookup": {}}])
def test_aggregation_stages_that_escape_the_tenant_are_refused(world, stage):
    with pytest.raises(mongo.TenantScopeError):
        mongo.scoped(p("BROKER_ADMIN", BROKER_A), mongo.JOBS).aggregate([stage])


def test_scope_requires_a_tenant_and_a_reading_role(world):
    for bad in (p("BROKER_ADMIN", None), p("EMPLOYER_ADMIN", BROKER_A, None), p("SYSTEM", None), p("TPA_USER", BROKER_A)):
        with pytest.raises(mongo.TenantScopeError):
            mongo.scoped(bad, mongo.JOBS)


def test_insert_cannot_contradict_the_scope(world):
    coll = mongo.scoped(p("BROKER_ADMIN", BROKER_A), mongo.RAG_DOCS)
    with pytest.raises(mongo.TenantScopeError):
        coll.insert_one({"_id": "x", "broker_id": BROKER_B})
    coll.insert_one({"_id": "y"})
    assert mongo.system_db()[mongo.RAG_DOCS].find_one({"_id": "y"})["broker_id"] == BROKER_A
