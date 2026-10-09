"""Service-to-service API for integration-service (SYSTEM tokens only, no tenant scoping: the caller is trusted infrastructure).

GET  /api/v1/internal/endorsements/<job>/members/?after=<id>&limit=<n>   keyset-paged roster rows last changed by that job
POST /api/v1/internal/endorsements/<job>/tpa-member-ids/                  {"members":[{"id":1,"tpa_member_id":"X"}]}
POST /api/v1/internal/endorsements/<job>/lifecycle/                       {"state":"SENT_TO_INSURER","reference":"INS-1","note":""}  post-endorsement milestones
GET  /api/v1/internal/policies/<policy>/members/?after=<id>&limit=<n>      keyset-paged ACTIVE roster of a policy (+ its policy_number, for TPA look-ups)
POST /api/v1/internal/policies/<policy>/tpa-member-ids/                    {"source":"TPA_SYNC","members":[{"id":1,"tpa_member_id":"X","tpa_member_name":"A B","ecard_url":"https://..."}]}
                                                                           (TPA reconciliation, the nightly pull, or an offline sheet a TPA or broker uploaded; id and/or e-card per item)
Rows are those whose `job` is still this job; a later endorsement that touches the same person supersedes them (documented gap)."""
from django.db import transaction
from django.db.models import Q
from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.permissions import BasePermission
from rest_framework.response import Response
from rest_framework.views import APIView

from .machine import InvalidTransition, StaleTransition, transition
from .models import EndorsementJob, EndorsementMember
from .states import LIFECYCLE_STATES, State

MAX_LIMIT = 1000


class IsSystem(BasePermission):
    def has_permission(self, request, view):
        return bool(request.user and getattr(request.user, "is_authenticated", False) and request.user.role == "SYSTEM")


def _member(m):
    return {"id": m.id, "employee_code": m.employee_code, "relation_code": m.relation_code, "first_name": m.first_name,
            "last_name": m.last_name, "date_of_birth": m.date_of_birth.isoformat(), "gender": m.gender, "email": m.email,
            "mobile": m.mobile, "sum_insured": str(m.sum_insured), "effective_date": m.effective_date.isoformat(),
            "end_date": m.end_date.isoformat() if m.end_date else None, "is_active": m.is_active, "tpa_member_id": m.tpa_member_id,
            "tpa_member_name": m.tpa_member_name, "ecard_url": m.ecard_url, "tpa_id_source": m.tpa_id_source,
            "tpa_id_assigned_at": m.tpa_id_assigned_at.isoformat() if m.tpa_id_assigned_at else None,
            "endorsement_ref": str(m.job_id)[:8], "job_id": str(m.job_id), "endorsed_at": m.created_at.isoformat()}


def _job(job_id):
    try:
        return EndorsementJob.objects.get(pk=job_id)
    except EndorsementJob.DoesNotExist:
        raise NotFound("No such job.")


class JobMembersView(APIView):
    permission_classes = [IsSystem]

    def get(self, request, job_id):
        job = _job(job_id)
        try:
            after = int(request.query_params.get("after", 0))
            limit = min(max(int(request.query_params.get("limit", 500)), 1), MAX_LIMIT)
        except ValueError:
            raise ValidationError("after and limit must be integers.")
        rows = list(EndorsementMember.objects.filter(job_id=job.id, id__gt=after).order_by("id")[:limit])
        return Response({"job_id": str(job.id), "policy_id": job.policy_id, "broker_id": job.broker_id, "op_type": job.op_type,
                         "members": [_member(m) for m in rows], "next_after": rows[-1].id if len(rows) == limit else None})


SOURCES = {"TPA_SYNC", "TPA_PORTAL", "BROKER_FOR_TPA"}


def _assign(policy_id, items, source):
    """Record what the TPA says about members of this policy: its member id, the name it holds, the e-card link. Any of the three per item,
    at least one. Returns (updated, ignored). Items naming members of another policy are ignored, never an error: a stale page cannot corrupt."""
    from django.utils import timezone
    if source not in SOURCES:
        raise ValidationError({"source": "must be one of " + ", ".join(sorted(SOURCES)) + "."})
    now = timezone.now()
    wanted = {}
    for it in items:
        if not isinstance(it, dict) or not isinstance(it.get("id"), int):
            raise ValidationError("each item needs an integer id.")
        fields = {}
        if str(it.get("tpa_member_id", "") or "").strip():
            fields["tpa_member_id"] = str(it["tpa_member_id"]).strip()[:60]
        if str(it.get("tpa_member_name", "") or "").strip():
            fields["tpa_member_name"] = str(it["tpa_member_name"]).strip()[:160]
        if str(it.get("ecard_url", "") or "").strip():
            fields["ecard_url"] = str(it["ecard_url"]).strip()[:500]
        if not fields.keys() & {"tpa_member_id", "ecard_url"}:
            raise ValidationError("each item needs a tpa_member_id and/or an ecard_url.")
        if "tpa_member_id" in fields:
            fields.update(tpa_id_source=source, tpa_id_assigned_at=now)
        wanted.setdefault(it["id"], {}).update(fields)               # the same member twice in one request: the later line wins
    # one statement per few hundred people, not a query per person: a 10,000-person sheet must not take long while a card is awaited
    from django.db import connection
    table = connection.ops.quote_name(EndorsementMember._meta.db_table)
    ids = sorted(wanted)
    updated = 0
    with connection.cursor() as cur:
        for i in range(0, len(ids), 500):
            chunk = ids[i:i + 500]
            values = ",".join(["(%s::bigint,%s::text,%s::text,%s::text)"] * len(chunk))
            params = []
            for mid in chunk:
                f = wanted[mid]
                params += [mid, f.get("tpa_member_id"), f.get("tpa_member_name"), f.get("ecard_url")]
            cur.execute(
                f"UPDATE {table} m SET tpa_member_id = COALESCE(v.tid, m.tpa_member_id), tpa_member_name = COALESCE(v.tname, m.tpa_member_name), "
                f"ecard_url = COALESCE(v.ecard, m.ecard_url), "
                f"tpa_id_source = CASE WHEN v.tid IS NOT NULL THEN %s ELSE m.tpa_id_source END, "
                f"tpa_id_assigned_at = CASE WHEN v.tid IS NOT NULL THEN %s::timestamptz ELSE m.tpa_id_assigned_at END, updated_at = %s::timestamptz "
                f"FROM (VALUES {values}) AS v(id, tid, tname, ecard) WHERE m.id = v.id AND m.policy_id = %s",
                [source, now, now, *params, policy_id])
            updated += cur.rowcount
    return updated, len(items) - updated


class TpaMemberIdsView(APIView):
    permission_classes = [IsSystem]

    @transaction.atomic
    def post(self, request, job_id):
        job = _job(job_id)
        items = request.data.get("members")
        if not isinstance(items, list) or not 0 < len(items) <= MAX_LIMIT:
            raise ValidationError(f"members must be a list of 1..{MAX_LIMIT} items.")
        updated, ignored = _assign(job.policy_id, items, str(request.data.get("source") or "TPA_SYNC"))
        return Response({"updated": updated, "ignored": ignored})


class LifecycleView(APIView):
    """integration-service reports where an endorsement is after the roster update: sent to the insurer, awaiting/received TPA
    confirmation, endorsement copy and e-cards, insurer-report approval. The state machine decides what is legal."""
    permission_classes = [IsSystem]

    def post(self, request, job_id):
        job = _job(job_id)
        name = str(request.data.get("state", "")).strip().upper()
        if name not in State.__members__ or State[name] not in LIFECYCLE_STATES:
            raise ValidationError({"state": "must be one of " + ", ".join(sorted(s.name for s in LIFECYCLE_STATES)) + "."})
        meta = {k: str(request.data[k])[:200] for k in ("reference", "note") if request.data.get(k)}
        target = State[name]
        if target == State.COMPLETED and job.failed_rows:
            target = State.PARTIAL_PROCESSED          # legacy PARTIAL_PROCESSED: finished, but some rows were rejected on the way
        try:
            job = transition(job.id, target, actor="integration-service", metadata=meta)
        except (InvalidTransition, StaleTransition) as e:
            return Response({"detail": str(e)}, status=409)
        return Response({"job_id": str(job.id), "state": job.status, "status_code": job.status_code, "phase": job.phase,
                         "category": job.category, "is_terminal": job.is_terminal})


class PolicyMembersView(APIView):
    """The policy's whole active roster, for reconciling it against what a TPA says it covers."""
    permission_classes = [IsSystem]

    def get(self, request, policy_id):
        try:
            after = int(request.query_params.get("after", 0))
            limit = min(max(int(request.query_params.get("limit", 500)), 1), MAX_LIMIT)
        except ValueError:
            raise ValidationError("after and limit must be integers.")
        qs = EndorsementMember.objects.filter(policy_id=policy_id, is_active=True, id__gt=after)
        pending = request.query_params.get("pending")           # ids: no TPA member id yet; ecards: has an id, no e-card yet; any: either
        if pending == "ids":
            qs = qs.filter(tpa_member_id__isnull=True)
        elif pending == "ecards":
            qs = qs.filter(ecard_url__isnull=True, tpa_member_id__isnull=False)
        elif pending == "any":
            qs = qs.filter(Q(tpa_member_id__isnull=True) | Q(ecard_url__isnull=True))
        elif pending:
            raise ValidationError("pending must be ids, ecards or any.")
        rows = list(qs.order_by("id")[:limit])
        latest = EndorsementJob.objects.filter(policy_id=policy_id).order_by("-created_at").first()
        facts = (latest.metadata or {}).get("policy", {}) if latest else {}
        return Response({"policy_id": policy_id, "broker_id": latest.broker_id if latest else None, "policy_number": facts.get("policy_number"),
                         "policy_start": facts.get("start_date"), "policy_end": facts.get("end_date"), "policy_is_inception": facts.get("is_inception"),
                         "members": [_member(m) for m in rows], "next_after": rows[-1].id if len(rows) == limit else None})


class PolicyTpaMemberIdsView(APIView):
    permission_classes = [IsSystem]

    @transaction.atomic
    def post(self, request, policy_id):
        items = request.data.get("members")
        if not isinstance(items, list) or not 0 < len(items) <= MAX_LIMIT:
            raise ValidationError(f"members must be a list of 1..{MAX_LIMIT} items.")
        updated, ignored = _assign(policy_id, items, str(request.data.get("source") or "TPA_SYNC"))
        return Response({"updated": updated, "ignored": ignored})


class PendingSummaryView(APIView):
    """How many members of each policy are still waiting for their TPA member id or e-card, and how long the longest has waited.
    GET /api/v1/internal/members/pending-summary/?policy_ids=1,2,3  (one grouped query, for the broker's and the TPA's "waiting" board)"""
    permission_classes = [IsSystem]

    def get(self, request):
        from django.db.models import Count, Min
        try:
            ids = [int(x) for x in str(request.query_params.get("policy_ids", "")).split(",") if x.strip()]
        except ValueError:
            raise ValidationError("policy_ids must be integers separated by commas.")
        if not 0 < len(ids) <= 500:
            raise ValidationError("policy_ids must name 1..500 policies.")
        rows = (EndorsementMember.objects.filter(policy_id__in=ids, is_active=True)
                .filter(Q(tpa_member_id__isnull=True) | Q(ecard_url__isnull=True))
                .values("policy_id")
                .annotate(pending_ids=Count("id", filter=Q(tpa_member_id__isnull=True)),
                          pending_ecards=Count("id", filter=Q(tpa_member_id__isnull=False, ecard_url__isnull=True)),
                          oldest_waiting=Min("created_at", filter=Q(tpa_member_id__isnull=True)),
                          oldest_card_waiting=Min("tpa_id_assigned_at", filter=Q(tpa_member_id__isnull=False, ecard_url__isnull=True))))
        return Response({"policies": [{"policy_id": r["policy_id"], "pending_ids": r["pending_ids"], "pending_ecards": r["pending_ecards"],
                                       "oldest_id_waiting_since": r["oldest_waiting"].isoformat() if r["oldest_waiting"] else None,
                                       "oldest_ecard_waiting_since": r["oldest_card_waiting"].isoformat() if r["oldest_card_waiting"] else None} for r in rows]})
