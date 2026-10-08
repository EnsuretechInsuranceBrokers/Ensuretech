import copy

import pytest

from apps.core import mongo
from apps.projections.handlers import PoisonEvent, handle_event

from .conftest import sample


def state(**kw):
    e = sample("endorsement-state-changed")
    e.update(kw)
    return e


def test_every_shared_event_sample_is_projected(db):
    for name in ("policy-approved", "endorsement-state-changed", "endorsement-applied", "cd-balance-updated", "tpa-sync-completed", "tpa-sync-failed"):
        assert handle_event(db, sample(name)) is True, name
    job = db[mongo.JOBS].find_one({"_id": sample("endorsement-state-changed")["job_id"]})
    assert job["state"] == "CANCELLED_BY_HR" and job["is_terminal"] is True and job["applied"]["total"] == "1765.15"
    assert db[mongo.POLICIES].find_one({"_id": 1}) ["tpa_id"] == 3
    cd = db[mongo.CD_ACCOUNTS].find_one({"_id": 1})
    assert cd["available"] == "3234.85" and cd["low"] is True                 # below the 5000.00 threshold
    assert {d["status"] for d in db[mongo.TPA_SYNCS].find()} == {"COMPLETED"} or db[mongo.TPA_SYNCS].count_documents({}) == 1


def test_redelivery_is_idempotent(db):
    for _ in range(3):
        handle_event(db, state())
        handle_event(db, sample("endorsement-applied"))
        handle_event(db, sample("tpa-sync-failed"))
    assert db[mongo.JOBS].count_documents({}) == 1
    assert db[mongo.JOB_HISTORY].count_documents({}) == 1
    assert db[mongo.RAG_CHUNKS].count_documents({}) == 3                           # state + applied + tpa failure, once each


def test_a_stale_state_event_never_overwrites_a_newer_one(db):
    handle_event(db, state(event_id="e2", to_state="ENDORSED", timestamp="2030-10-02T10:00:00+00:00", is_terminal=True))
    handle_event(db, state(event_id="e1", to_state="CD_SUFFICIENT", timestamp="2030-10-01T10:00:00+00:00", is_terminal=False))   # arrives late
    job = db[mongo.JOBS].find_one()
    assert job["state"] == "ENDORSED" and job["is_terminal"] is True
    assert db[mongo.JOB_HISTORY].count_documents({}) == 2                          # but the history still records both, in time order
    assert [h["to_state"] for h in db[mongo.JOB_HISTORY].find().sort("ts", 1)] == ["CD_SUFFICIENT", "ENDORSED"]


def test_applied_before_state_event_still_converges(db):
    handle_event(db, sample("endorsement-applied"))
    handle_event(db, state())
    job = db[mongo.JOBS].find_one()
    assert job["state"] == "CANCELLED_BY_HR" and job["applied"]["members_changed"] == 3 and job["broker_id"] == 1


def test_cd_low_flag_follows_the_threshold(db):
    e = sample("cd-balance-updated")
    handle_event(db, e)
    assert db[mongo.CD_ACCOUNTS].find_one()["low"] is True
    handle_event(db, {**e, "available": "9000.00", "balance": "9000.00"})
    assert db[mongo.CD_ACCOUNTS].find_one()["low"] is False
    handle_event(db, {k: v for k, v in e.items() if k != "threshold"})
    assert db[mongo.CD_ACCOUNTS].find_one()["low"] is None                        # unknown, not guessed


def test_no_member_pii_is_ever_stored(db):
    handle_event(db, state())
    handle_event(db, sample("endorsement-applied"))
    handle_event(db, sample("tpa-sync-completed"))
    blob = str(list(db[mongo.JOBS].find())) + str(list(db[mongo.TPA_SYNCS].find())) + str(list(db[mongo.RAG_CHUNKS].find({}, {"vec": 0})))
    for forbidden in ("date_of_birth", "pan", "mobile", "email", "first_name"):
        assert forbidden not in blob


@pytest.mark.parametrize("bad", [
    lambda: {**state(), "timestamp": "not a time"}, lambda: {k: v for k, v in state().items() if k != "job_id"},
    lambda: {**state(), "broker_id": "1"}, lambda: {**state(), "employer_id": True}, lambda: [1, 2],
    lambda: {**sample("cd-balance-updated"), "available": "NaN-ish"}, lambda: {k: v for k, v in sample("policy-approved").items() if k != "policy_id"},
])
def test_malformed_events_are_poison_not_crashes(db, bad):
    with pytest.raises(PoisonEvent):
        handle_event(db, bad())


def test_unknown_event_types_are_ignored(db):
    assert handle_event(db, {"event_type": "SomethingNew", "x": 1}) is False
    assert handle_event(db, {"nope": 1}) is False


def test_a_shared_deposit_account_updates_every_member_policy_and_a_cross_employer_pool_has_no_employer(db):
    e = {**sample("cd-balance-updated"), "account_type": "GROUP", "policy_ids": [1, 2, 3]}
    handle_event(db, e)
    docs = list(db[mongo.CD_ACCOUNTS].find().sort("_id", 1))
    assert [d["_id"] for d in docs] == [1, 2, 3] and {d["balance"] for d in docs} == {str(_money_of(e["balance"]))}
    assert all(d["account_type"] == "GROUP" and d["employer_id"] == 10 for d in docs)
    db[mongo.CD_ACCOUNTS].delete_many({})
    handle_event(db, {**e, "account_type": "MULTIPLE", "employer_id": None})
    assert {d["employer_id"] for d in db[mongo.CD_ACCOUNTS].find()} == {None}


def _money_of(v):
    from decimal import Decimal
    return Decimal(v).quantize(Decimal("0.01"))
