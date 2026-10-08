"""Service-to-service API for integration-service (SYSTEM tokens only, no tenant scoping: the caller is trusted infrastructure).

GET  /api/v1/internal/endorsements/<job>/members/?after=<id>&limit=<n>   keyset-paged roster rows last changed by that job
POST /api/v1/internal/endorsements/<job>/tpa-member-ids/                  {"members":[{"id":1,"tpa_member_id":"X"}]}
POST /api/v1/internal/endorsements/<job>/lifecycle/                       {"state":"SENT_TO_INSURER","reference":"INS-1","note":""}  post-endorsement milestones
GET  /api/v1/internal/policies/<policy>/members/?after=<id>&limit=<n>      keyset-paged ACTIVE roster of a policy (+ its policy_number, for TPA look-ups)
POST /api/v1/internal/policies/<policy>/tpa-member-ids/                    {"members":[{"id":1,"tpa_member_id":"X"}]}  (TPA reconciliation)
Rows are those whose `job` is still this job; a later endorsement that touches the same person supersedes them (documented gap)."""
from django.db import transaction
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
            "end_date": m.end_date.isoformat() if m.end_date else None, "is_active": m.is_active, "tpa_member_id": m.tpa_member_id}


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


class TpaMemberIdsView(APIView):
    permission_classes = [IsSystem]

    @transaction.atomic
    def post(self, request, job_id):
        job = _job(job_id)
        items = request.data.get("members")
        if not isinstance(items, list) or not 0 < len(items) <= MAX_LIMIT:
            raise ValidationError(f"members must be a list of 1..{MAX_LIMIT} items.")
        updated = 0
        for it in items:
            if not isinstance(it, dict) or not isinstance(it.get("id"), int) or not str(it.get("tpa_member_id", "")).strip():
                raise ValidationError("each item needs an integer id and a non-empty tpa_member_id.")
            updated += EndorsementMember.objects.filter(id=it["id"], policy_id=job.policy_id).update(
                tpa_member_id=str(it["tpa_member_id"])[:60])
        return Response({"updated": updated, "ignored": len(items) - updated})


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
        rows = list(EndorsementMember.objects.filter(policy_id=policy_id, is_active=True, id__gt=after).order_by("id")[:limit])
        latest = EndorsementJob.objects.filter(policy_id=policy_id).order_by("-created_at").first()
        facts = (latest.metadata or {}).get("policy", {}) if latest else {}
        return Response({"policy_id": policy_id, "broker_id": latest.broker_id if latest else None, "policy_number": facts.get("policy_number"),
                         "members": [_member(m) for m in rows], "next_after": rows[-1].id if len(rows) == limit else None})


class PolicyTpaMemberIdsView(APIView):
    permission_classes = [IsSystem]

    @transaction.atomic
    def post(self, request, policy_id):
        items = request.data.get("members")
        if not isinstance(items, list) or not 0 < len(items) <= MAX_LIMIT:
            raise ValidationError(f"members must be a list of 1..{MAX_LIMIT} items.")
        updated = 0
        for it in items:
            if not isinstance(it, dict) or not isinstance(it.get("id"), int) or not str(it.get("tpa_member_id", "")).strip():
                raise ValidationError("each item needs an integer id and a non-empty tpa_member_id.")
            updated += EndorsementMember.objects.filter(id=it["id"], policy_id=policy_id).update(tpa_member_id=str(it["tpa_member_id"])[:60])
        return Response({"updated": updated, "ignored": len(items) - updated})
