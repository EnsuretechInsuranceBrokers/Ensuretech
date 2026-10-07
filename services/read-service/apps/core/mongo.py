"""MongoDB access. Two deliberately separate doors:

* `system_db()` is for the projector only (it writes every tenant's events).
* `scoped(principal, name)` is for everything that answers a user. The tenant filter is merged into every operation,
  so no call site can forget it, and aggregation stages that could reach other collections or write are refused.

Collections: jobs, job_history, policies, cd_accounts, tpa_syncs, rag_chunks, rag_documents, projector_dead_letters.
"""
from functools import lru_cache

from django.conf import settings
from pymongo import ASCENDING, DESCENDING, MongoClient

JOBS, JOB_HISTORY, POLICIES, CD_ACCOUNTS, TPA_SYNCS = "jobs", "job_history", "policies", "cd_accounts", "tpa_syncs"
RAG_CHUNKS, RAG_DOCS, DEAD_LETTERS = "rag_chunks", "rag_documents", "projector_dead_letters"
PROJECTION_COLLECTIONS = (JOBS, JOB_HISTORY, POLICIES, CD_ACCOUNTS, TPA_SYNCS)
EMPLOYER_VISIBLE_AUDIENCES = ["EMPLOYER", "HISTORY"]
_FORBIDDEN_STAGES = {"$lookup", "$graphLookup", "$out", "$merge", "$unionWith", "$facet", "$collStats", "$indexStats", "$currentOp", "$function"}


@lru_cache(maxsize=1)
def _client() -> MongoClient:
    return MongoClient(settings.MONGO_URI, serverSelectionTimeoutMS=settings.MONGO_TIMEOUT_MS, tz_aware=True)


def system_db():
    return _client()[settings.MONGO_DB]


def reset_client():
    _client.cache_clear()


class TenantScopeError(Exception):
    pass


def scope_for(principal, collection: str) -> dict:
    """The filter every query on `collection` is forced through, derived only from the verified token."""
    if principal.broker_id is None:
        raise TenantScopeError("principal has no broker scope")
    scope = {"broker_id": principal.broker_id}
    if principal.is_employer:
        if principal.employer_id is None:
            raise TenantScopeError("employer principal has no employer scope")
        scope["employer_id"] = principal.employer_id
        if collection in (RAG_CHUNKS, RAG_DOCS):
            scope["audience"] = {"$in": EMPLOYER_VISIBLE_AUDIENCES}
    elif not principal.is_broker:
        raise TenantScopeError("role may not read tenant data")
    return scope


class ScopedCollection:
    def __init__(self, coll, scope: dict):
        self._c, self._scope = coll, scope

    def _q(self, flt):
        return {"$and": [self._scope, flt]} if flt else dict(self._scope)

    def find(self, flt=None, projection=None, sort=None, skip=0, limit=0):
        cur = self._c.find(self._q(flt), projection)
        if sort:
            cur = cur.sort(sort)
        return cur.skip(skip).limit(limit)

    def find_one(self, flt=None, projection=None):
        return self._c.find_one(self._q(flt), projection)

    def count(self, flt=None):
        return self._c.count_documents(self._q(flt))

    def aggregate(self, pipeline):
        for stage in pipeline:
            if set(stage) & _FORBIDDEN_STAGES:
                raise TenantScopeError(f"aggregation stage not allowed: {sorted(set(stage) & _FORBIDDEN_STAGES)}")
        return self._c.aggregate([{"$match": self._scope}, *pipeline])

    def insert_one(self, doc: dict):
        fixed = {k: v for k, v in self._scope.items() if not isinstance(v, dict)}     # equality parts of the scope are stamped on
        for k, v in fixed.items():
            if k in doc and doc[k] != v:
                raise TenantScopeError(f"document field {k} contradicts the caller's scope")
        return self._c.insert_one({**doc, **fixed})

    def delete_one(self, flt):
        return self._c.delete_one(self._q(flt))

    def delete_many(self, flt):
        return self._c.delete_many(self._q(flt))


def scoped(principal, name: str) -> ScopedCollection:
    return ScopedCollection(system_db()[name], scope_for(principal, name))


def ensure_indexes():
    db = system_db()
    db[JOBS].create_index([("broker_id", ASCENDING), ("employer_id", ASCENDING), ("state_ts", DESCENDING)])
    db[JOBS].create_index([("broker_id", ASCENDING), ("policy_id", ASCENDING)])
    db[JOB_HISTORY].create_index([("job_id", ASCENDING), ("ts", ASCENDING)])
    db[POLICIES].create_index([("broker_id", ASCENDING), ("employer_id", ASCENDING)])
    db[CD_ACCOUNTS].create_index([("broker_id", ASCENDING), ("employer_id", ASCENDING)])
    db[TPA_SYNCS].create_index([("broker_id", ASCENDING), ("policy_id", ASCENDING)])
    db[RAG_CHUNKS].create_index([("broker_id", ASCENDING), ("employer_id", ASCENDING), ("policy_id", ASCENDING)])
    db[RAG_CHUNKS].create_index([("source_type", ASCENDING), ("source_id", ASCENDING)])
