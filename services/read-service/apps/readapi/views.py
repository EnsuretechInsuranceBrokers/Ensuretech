from datetime import datetime

from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core import mongo

MAX_PAGE_SIZE = 200


def _out(doc):
    if doc is None:
        return None
    d = {("id" if k == "_id" else k): (v.isoformat() if isinstance(v, datetime) else v) for k, v in doc.items() if k != "vec"}
    return d


def _page(request):
    try:
        size = min(max(int(request.query_params.get("page_size", 50)), 1), MAX_PAGE_SIZE)
        page = max(int(request.query_params.get("page", 1)), 1)
    except ValueError:
        raise ValidationError("page and page_size must be integers")
    return page, size


def _int_param(request, name):
    v = request.query_params.get(name)
    if v is None:
        return None
    try:
        return int(v)
    except ValueError:
        raise ValidationError({name: "must be an integer"})


def _bool_param(request, name):
    v = request.query_params.get(name)
    if v is None:
        return None
    if v.lower() not in ("true", "false"):
        raise ValidationError({name: "must be true or false"})
    return v.lower() == "true"


def _listing(request, collection, filters, sort):
    """filters: {param: caster}. Only whitelisted params reach the query, always as typed equality values, never as operators."""
    flt = {}
    for param, cast in filters.items():
        v = cast(request, param) if cast in (_int_param, _bool_param) else request.query_params.get(param)
        if v is not None:
            flt[param] = v
    coll = mongo.scoped(request.user, collection)
    page, size = _page(request)
    total = coll.count(flt)
    rows = coll.find(flt or None, sort=sort, skip=(page - 1) * size, limit=size)
    return Response({"count": total, "page": page, "page_size": size, "results": [_out(r) for r in rows]})


class JobListView(APIView):
    def get(self, request):
        return _listing(request, mongo.JOBS, {"state": None, "category": None, "op_type": None, "policy_id": _int_param, "is_terminal": _bool_param},
                        [("state_ts", -1), ("_id", 1)])


class JobDetailView(APIView):
    def get(self, request, job_id):
        job = mongo.scoped(request.user, mongo.JOBS).find_one({"_id": job_id})
        if not job:
            raise NotFound()
        history = mongo.scoped(request.user, mongo.JOB_HISTORY).find({"job_id": job_id}, sort=[("ts", 1)], limit=500)
        return Response({**_out(job), "history": [_out(h) for h in history]})


class PolicyListView(APIView):
    def get(self, request):
        return _listing(request, mongo.POLICIES, {"status": None, "tpa_id": _int_param}, [("_id", 1)])


class PolicyDetailView(APIView):
    def get(self, request, policy_id):
        pol = mongo.scoped(request.user, mongo.POLICIES).find_one({"_id": policy_id})
        if not pol:
            raise NotFound()
        cd = mongo.scoped(request.user, mongo.CD_ACCOUNTS).find_one({"_id": policy_id})
        syncs = list(mongo.scoped(request.user, mongo.TPA_SYNCS).find({"policy_id": policy_id}, sort=[("updated_at", -1)], limit=5))
        return Response({**_out(pol), "cd_account": _out(cd), "recent_tpa_syncs": [_out(s) for s in syncs]})


class CdListView(APIView):
    def get(self, request):
        return _listing(request, mongo.CD_ACCOUNTS, {"low": _bool_param}, [("_id", 1)])


class DashboardView(APIView):
    """Everything is computed at read time from the projections with scoped aggregations; nothing is pre-aggregated."""

    def get(self, request):
        jobs = mongo.scoped(request.user, mongo.JOBS)
        by_state = {r["_id"]: r["n"] for r in jobs.aggregate([{"$group": {"_id": "$state", "n": {"$sum": 1}}}]) if r["_id"]}
        return Response({
            "jobs": {"total": jobs.count(), "open": jobs.count({"is_terminal": False}), "by_state": by_state},
            "cd": {"accounts": mongo.scoped(request.user, mongo.CD_ACCOUNTS).count(),
                   "low": mongo.scoped(request.user, mongo.CD_ACCOUNTS).count({"low": True})},
            "tpa_syncs": {"failed": mongo.scoped(request.user, mongo.TPA_SYNCS).count({"status": "FAILED"}),
                          "completed": mongo.scoped(request.user, mongo.TPA_SYNCS).count({"status": "COMPLETED"})},
        })
