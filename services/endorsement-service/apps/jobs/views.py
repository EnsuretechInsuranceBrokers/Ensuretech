from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.exceptions import Conflict
from apps.core.permissions import CAN_DECIDE, GatewayPermission
from apps.core.tenancy import get_scoped_or_404, scope_filter

from .machine import InvalidTransition, transition
from .models import EndorsementJob
from .serializers import audit_to_dict, job_to_dict
from .states import State


def _page(request, default=50, maximum=200):
    try:
        size = min(max(int(request.query_params.get("page_size", default)), 1), maximum)
        page = max(int(request.query_params.get("page", 1)), 1)
    except ValueError:
        size, page = default, 1
    return page, size


class JobListView(APIView):
    def get(self, request):
        qs = EndorsementJob.objects.filter(**scope_filter(request.user), is_test_data=False)
        for field in ("status", "policy_id", "source_type", "op_type"):
            if field in request.query_params:
                qs = qs.filter(**{field: request.query_params[field]})
        page, size = _page(request)
        total = qs.count()
        rows = qs.order_by("-created_at")[(page - 1) * size: page * size]
        return Response({"count": total, "page": page, "page_size": size, "results": [job_to_dict(j) for j in rows]})


class JobDetailView(APIView):
    def get(self, request, job_id):
        return Response(job_to_dict(get_scoped_or_404(EndorsementJob.objects, request.user, id=job_id)))


class JobAuditView(APIView):
    def get(self, request, job_id):
        job = get_scoped_or_404(EndorsementJob.objects, request.user, id=job_id)
        return Response([audit_to_dict(a) for a in job.audit.all()])


class JobCancelView(APIView):
    permission_classes = [GatewayPermission, CAN_DECIDE]

    def post(self, request, job_id):
        job = get_scoped_or_404(EndorsementJob.objects, request.user, id=job_id)
        try:
            job = transition(job.id, State.CANCELLED_BY_HR, actor=request.user.user_id,
                             actor_ip=request.META.get("REMOTE_ADDR"))
        except InvalidTransition as e:
            raise Conflict(str(e)) from e
        return Response(job_to_dict(job))


class JobProceedView(APIView):
    """HR/broker confirms: continue with the valid rows (VALIDATION_PARTIAL) or release a held clean job."""
    permission_classes = [GatewayPermission, CAN_DECIDE]

    def post(self, request, job_id):
        from apps.computation.tasks import start_premium_calculation
        job = get_scoped_or_404(EndorsementJob.objects, request.user, id=job_id)
        if job.status not in (State.VALIDATION_PASSED.name, State.VALIDATION_PARTIAL.name):
            raise Conflict(f"nothing to confirm: job is {job.status}")
        start_premium_calculation.apply_async(args=[str(job.id), request.user.user_id])
        job.refresh_from_db()
        return Response(job_to_dict(job), status=202)


class JobRetryView(APIView):
    """Re-run the step a job is stuck on, once the cause is fixed (rate card corrected, deposit topped up, ...)."""
    permission_classes = [GatewayPermission, CAN_DECIDE]

    def post(self, request, job_id):
        from apps.computation.tasks import check_cd_balance, queue_endorsement, start_premium_calculation
        job = get_scoped_or_404(EndorsementJob.objects, request.user, id=job_id)
        actor = request.user.user_id
        stuck = State[job.status]
        if stuck == State.MANUAL_INTERVENTION_REQUIRED:
            last = job.audit.filter(to_state=stuck.name).order_by("-id").first()
            stuck = State[last.from_state] if last else stuck
        S = State
        try:
            if stuck == S.PREMIUM_CALC_FAILED:
                start_premium_calculation.apply_async(args=[str(job.id), actor])
            elif stuck in (S.CD_INSUFFICIENT, S.CD_PARTIAL_SUFFICIENT, S.CD_CHECK_FAILED):
                EndorsementJob.objects.filter(pk=job.id).update(retry_count=0)
                transition(job.id, S.CD_CHECK_INITIATED, actor=actor, metadata={"retry_of": stuck.name})
                check_cd_balance.apply_async(args=[str(job.id), actor])
            elif stuck == S.ENDORSEMENT_FAILED:
                transition(job.id, S.ENDORSEMENT_QUEUED, actor=actor)
                from apps.computation import tasks as t
                from apps.jobs import chunks
                chunks.reset_stage(job.id, t.ENDORSE)
                chunks.ensure_stage(job.id, t.ENDORSE, job.chunk_count)
                EndorsementJob.objects.filter(pk=job.id).update(chunks_endorsed=0)
                for i in chunks.stage_indexes(job.id, t.ENDORSE):
                    t.apply_chunk_task.apply_async(args=[str(job.id), i])
            else:
                raise Conflict(f"job is {job.status}; there is nothing to retry")
        except InvalidTransition as e:
            raise Conflict(str(e)) from e
        job.refresh_from_db()
        return Response(job_to_dict(job), status=202)
