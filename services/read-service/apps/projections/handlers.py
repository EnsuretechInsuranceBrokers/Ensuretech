"""Event -> MongoDB read models. Every handler is idempotent (upserts keyed by an id carried in the event), so Kafka's
at-least-once delivery, replays and `rebuild_projections` are all safe.

Ordering: per-broker order within a topic comes from Kafka's partition key (broker_id). Where an event carries a timestamp
(EndorsementStateChanged) a stale one never overwrites a newer one. Events across topics have no ordering guarantee and none is
assumed. No member PII is projected: roster data stays in the engine's PostgreSQL.
"""
import logging
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation

from pymongo.errors import DuplicateKeyError

from apps.core import mongo
from apps.rag import store

log = logging.getLogger(__name__)


class PoisonEvent(ValueError):
    """The event can never be processed (malformed or missing fields). Dead-lettered, not retried."""


def _req(e: dict, *keys):
    missing = [k for k in keys if e.get(k) is None]
    if missing:
        raise PoisonEvent(f"{e.get('event_type')}: missing {missing}")


def _int(e: dict, key: str) -> int:
    v = e.get(key)
    if isinstance(v, bool) or not isinstance(v, int):
        raise PoisonEvent(f"{e.get('event_type')}: {key} must be an integer")
    return v


def _ts(v) -> datetime:
    try:
        d = datetime.fromisoformat(str(v))
    except ValueError as ex:
        raise PoisonEvent(f"bad timestamp {v!r}") from ex
    return d if d.tzinfo else d.replace(tzinfo=UTC)


def _money(v) -> Decimal:
    try:
        return Decimal(str(v))
    except InvalidOperation as ex:
        raise PoisonEvent(f"bad amount {v!r}") from ex


def _chunk(db, event: dict, source_id: str, title: str, text: str, policy_id=None):
    store.upsert_chunks(db, [{"_id": f"hist:{event['event_id']}", "broker_id": _int(event, "broker_id"), "employer_id": _int(event, "employer_id"),
                              "audience": "HISTORY", "policy_id": policy_id, "source_type": "HISTORY", "source_id": source_id, "title": title}], [text])


def endorsement_state_changed(db, e):
    _req(e, "event_id", "job_id", "to_state", "timestamp")
    broker, employer, ts = _int(e, "broker_id"), _int(e, "employer_id"), _ts(e["timestamp"])
    db[mongo.JOB_HISTORY].replace_one({"_id": e["event_id"]}, {
        "_id": e["event_id"], "job_id": e["job_id"], "broker_id": broker, "employer_id": employer, "policy_id": e.get("policy_id"),
        "from_state": e.get("from_state"), "to_state": e["to_state"], "phase": e.get("phase"), "category": e.get("category"),
        "actor": e.get("actor"), "ts": ts}, upsert=True)
    try:        # only a strictly newer state wins; if a newer one exists the filter misses and the upsert collides on _id
        db[mongo.JOBS].update_one(
            {"_id": e["job_id"], "$or": [{"state_ts": {"$lt": ts}}, {"state_ts": {"$exists": False}}]},
            {"$set": {"broker_id": broker, "employer_id": employer, "policy_id": e.get("policy_id"), "source_type": e.get("source_type"),
                      "op_type": e.get("op_type"), "state": e["to_state"], "phase": e.get("phase"), "category": e.get("category"),
                      "is_terminal": bool(e.get("is_terminal")), "state_ts": ts}}, upsert=True)
    except DuplicateKeyError:
        log.debug("stale state event for job %s ignored", e["job_id"])
    if e["to_state"] == "ENDORSED":     # order-independent: the earliest ENDORSED transition is when the roster changed (reporting by month)
        db[mongo.JOBS].update_one({"_id": e["job_id"], "$or": [{"endorsed_at": {"$gt": ts}}, {"endorsed_at": {"$exists": False}}]},
                                  {"$set": {"endorsed_at": ts}})
    _chunk(db, e, e["job_id"], f"Endorsement job {e['job_id'][:8]}",
           f"Endorsement job {e['job_id']} for policy {e.get('policy_id')} ({e.get('op_type')} via {e.get('source_type')}) moved from "
           f"{e.get('from_state') or 'creation'} to {e['to_state']} (phase {e.get('phase')}, category {e.get('category')}) at {ts.isoformat()}.",
           e.get("policy_id"))


def endorsement_applied(db, e):
    _req(e, "event_id", "job_id")
    broker, employer = _int(e, "broker_id"), _int(e, "employer_id")
    db[mongo.JOBS].update_one({"_id": e["job_id"]}, {
        "$set": {"applied": {"members_changed": e.get("members_changed"), "premium_delta": e.get("premium_delta"),
                             "tax_amount": e.get("tax_amount"), "total": e.get("total")}},
        "$setOnInsert": {"broker_id": broker, "employer_id": employer, "policy_id": e.get("policy_id"), "op_type": e.get("op_type"),
                         "source_type": e.get("source_type")}}, upsert=True)
    _chunk(db, e, e["job_id"], f"Endorsement job {e['job_id'][:8]}",
           f"Endorsement job {e['job_id']} was applied to policy {e.get('policy_id')}: {e.get('members_changed')} member(s) changed, "
           f"premium delta {e.get('premium_delta')}, tax {e.get('tax_amount')}, total {e.get('total')}.", e.get("policy_id"))


def policy_event(db, e):
    _req(e, "policy_id")
    pid, broker, employer = _int(e, "policy_id"), _int(e, "broker_id"), _int(e, "employer_id")
    fields = {k: e[k] for k in ("policy_number", "status", "tpa_id", "premium_type") if k in e}
    db[mongo.POLICIES].update_one({"_id": pid}, {"$set": {**fields, "broker_id": broker, "employer_id": employer, "last_event": e["event_type"]}},
                                  upsert=True)


def cd_balance_updated(db, e):
    _req(e, "policy_id", "balance", "reserved", "available")
    pid, broker, employer = _int(e, "policy_id"), _int(e, "broker_id"), _int(e, "employer_id")
    available, threshold = _money(e["available"]), _money(e["threshold"]) if e.get("threshold") is not None else None
    db[mongo.CD_ACCOUNTS].update_one({"_id": pid}, {"$set": {
        "broker_id": broker, "employer_id": employer, "balance": str(_money(e["balance"])), "reserved": str(_money(e["reserved"])),
        "available": str(available), "threshold": None if threshold is None else str(threshold),
        "low": None if threshold is None else available < threshold, "updated_at": datetime.now(UTC)}}, upsert=True)


def tpa_sync(db, e):
    _req(e, "event_id", "sync_job_id")
    broker, employer = _int(e, "broker_id"), _int(e, "employer_id")
    failed = e["event_type"] == "TpaSyncFailed"
    db[mongo.TPA_SYNCS].update_one({"_id": e["sync_job_id"]}, {"$set": {
        "broker_id": broker, "employer_id": employer, "policy_id": e.get("policy_id"), "endorsement_job_id": e.get("endorsement_job_id"),
        "tpa_id": e.get("tpa_id"), "status": "FAILED" if failed else "COMPLETED", "members_expected": e.get("members_expected"),
        "synced": e.get("synced"), "rejected": e.get("rejected"), "error": e.get("error"), "attempts": e.get("attempts"),
        "updated_at": datetime.now(UTC)}}, upsert=True)
    outcome = (f"FAILED after {e.get('attempts')} attempt(s): {e.get('error')}" if failed
               else f"completed: {e.get('synced')} synced, {e.get('rejected')} rejected of {e.get('members_expected')}")
    _chunk(db, e, e["sync_job_id"], f"TPA sync {e['sync_job_id'][:8]}",
           f"TPA sync for endorsement job {e.get('endorsement_job_id')} on policy {e.get('policy_id')} (TPA {e.get('tpa_id')}) {outcome}.", e.get("policy_id"))


HANDLERS = {
    "EndorsementStateChanged": endorsement_state_changed, "EndorsementApplied": endorsement_applied,
    "PolicyCreated": policy_event, "PolicyApproved": policy_event, "PolicyDeactivated": policy_event,
    "CdBalanceUpdated": cd_balance_updated, "TpaSyncCompleted": tpa_sync, "TpaSyncFailed": tpa_sync,
}


def handle_event(db, event: dict) -> bool:
    """True if the event type is one we project. Raises PoisonEvent for unprocessable events; pymongo errors propagate (transient)."""
    if not isinstance(event, dict):
        raise PoisonEvent("event is not a JSON object")
    fn = HANDLERS.get(event.get("event_type"))
    if fn is None:
        return False
    fn(db, event)
    return True
