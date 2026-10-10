from collections import defaultdict
from datetime import datetime, timedelta, timezone

from rest_framework.exceptions import ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core import mongo

# Legacy red-flag thresholds (constant.php RedFlags): endorsement pending 7 days; multiple endorsements >= 3.
PENDING_DAYS = 7
BLOCKED_STATES = {"CD_INSUFFICIENT", "CD_PARTIAL_SUFFICIENT", "CD_CHECK_FAILED", "MANUAL_INTERVENTION_REQUIRED", "VALIDATION_PARTIAL"}


def _int_list(request, name):
    v = request.query_params.get(name)
    if v is None:
        return None
    try:
        return int(v)
    except ValueError:
        raise ValidationError({name: "must be an integer"})


class EndorsementActivityView(APIView):
    """Members added / updated / removed per month (the legacy 'endorsement-wise stat count' widget): bucketed by the month the
    roster actually changed (`endorsed_at`), optionally for one policy and a date range. Computed from the jobs projection."""

    def get(self, request):
        flt = {"endorsed_at": {"$exists": True}}
        if (pid := _int_list(request, "policy_id")) is not None:
            flt["policy_id"] = pid
        rng = {}
        for key, op in (("from", "$gte"), ("to", "$lte")):
            if (v := request.query_params.get(key)) is not None:
                try:
                    rng[op] = datetime.fromisoformat(v).replace(tzinfo=timezone.utc) + (timedelta(days=1, microseconds=-1) if key == "to" else timedelta())
                except ValueError:
                    raise ValidationError({key: "use YYYY-MM-DD"})
        if rng:
            flt["endorsed_at"] = {**flt["endorsed_at"], **rng}
        months = defaultdict(lambda: {"ADD": 0, "UPDATE": 0, "REMOVE": 0, "jobs": 0})
        for j in mongo.scoped(request.user, mongo.JOBS).find(flt, {"endorsed_at": 1, "op_type": 1, "applied": 1}, limit=100_000):
            m = months[j["endorsed_at"].strftime("%Y-%m")]
            m["jobs"] += 1
            if j.get("op_type") in ("ADD", "UPDATE", "REMOVE"):
                m[j["op_type"]] += int((j.get("applied") or {}).get("members_changed") or 0)
        return Response({"months": [{"month": k, "added": v["ADD"], "updated": v["UPDATE"], "removed": v["REMOVE"], "endorsements": v["jobs"]}
                                    for k, v in sorted(months.items())]})


class RedFlagsView(APIView):
    """Things that need a human: deposits below their threshold, endorsements stuck or blocked, TPA syncs that gave up."""

    def get(self, request):
        pending_days = _int_list(request, "pending_days") or PENDING_DAYS
        cutoff = datetime.now(timezone.utc) - timedelta(days=pending_days)
        flags = []
        for a in mongo.scoped(request.user, mongo.CD_ACCOUNTS).find({"low": True}, limit=500):
            flags.append({"type": "CD_BALANCE", "priority": "High" if float(a["available"]) <= 0 else "Medium", "policy_id": a["_id"],
                          "detail": f"available {a['available']} is below the threshold {a['threshold']}"})
        jobs = mongo.scoped(request.user, mongo.JOBS)
        for j in jobs.find({"is_terminal": False, "state_ts": {"$lt": cutoff}}, limit=500):
            flags.append({"type": "ENDORSEMENT", "priority": "High", "job_id": j["_id"], "policy_id": j.get("policy_id"),
                          "detail": f"in {j['state']} since {j['state_ts'].date().isoformat()} (more than {pending_days} days)"})
        for j in jobs.find({"is_terminal": False, "state": {"$in": sorted(BLOCKED_STATES)}, "state_ts": {"$gte": cutoff}}, limit=500):
            flags.append({"type": "ENDORSEMENT", "priority": "Medium", "job_id": j["_id"], "policy_id": j.get("policy_id"), "detail": f"waiting in {j['state']}"})
        for s in mongo.scoped(request.user, mongo.TPA_SYNCS).find({"status": "FAILED"}, limit=500):
            flags.append({"type": "TPA_SYNC", "priority": "High", "policy_id": s.get("policy_id"), "sync_id": s["_id"], "detail": f"failed: {s.get('error')}"})
        order = {"Critical": 0, "High": 1, "Medium": 2, "Low": 3}
        flags.sort(key=lambda f: order[f["priority"]])
        return Response({"count": len(flags), "pending_days": pending_days, "flags": flags})
