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
