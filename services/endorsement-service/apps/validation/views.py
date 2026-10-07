from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.tenancy import get_scoped_or_404
from apps.jobs.models import EndorsementJob

from .models import ValidationLog


class JobErrorsView(APIView):
    """Row-level validation findings: exactly which line and field, and why. Paginated; filter by severity/rule."""

    def get(self, request, job_id):
        job = get_scoped_or_404(EndorsementJob.objects, request.user, id=job_id)
        qs = ValidationLog.objects.filter(job_id=job.id)
        for q, col in (("severity", "severity"), ("rule", "rule_code"), ("employee_code", "employee_code")):
            if q in request.query_params:
                qs = qs.filter(**{col: request.query_params[q]})
        try:
            size = min(max(int(request.query_params.get("page_size", 100)), 1), 1000)
            page = max(int(request.query_params.get("page", 1)), 1)
        except ValueError:
            size, page = 100, 1
        total = qs.count()
        rows = qs.order_by("source_line", "id")[(page - 1) * size: page * size]
        return Response({"count": total, "page": page, "page_size": size, "summary": job.error_summary,
                         "results": [{"line": r.source_line, "employee_code": r.employee_code, "field": r.field_name,
                                      "rule": r.rule_code, "severity": r.severity, "message": r.message,
                                      "value": r.raw_value} for r in rows]})
