import datetime as dt

from django.core.cache import cache
from django.utils import timezone
from rest_framework import status
from rest_framework.exceptions import NotFound, Throttled, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.exceptions import Conflict, Unprocessable
from apps.core.permissions import CAN_SUBMIT, GatewayPermission
from apps.core.tenancy import scope_filter

from . import gui
from .models import GuiRow, GuiSession
from .views import _policy, _policy_facts

PERMS = [GatewayPermission, CAN_SUBMIT]


class Gone(Conflict):
    status_code = status.HTTP_410_GONE
    default_detail = "Session expired"


def _session(request, session_id, *, mutate=False) -> GuiSession:
    qs = GuiSession.objects.filter(**scope_filter(request.user), id=session_id)
    s = qs.first()
    if s is None:
        raise NotFound()
    if mutate:
        if s.state == "OPEN" and s.expires_at < timezone.now():
            GuiSession.objects.filter(pk=s.pk).update(state="ABANDONED")
            raise Gone("session expired; start a new one")
        if s.state != "OPEN":
            raise Conflict(f"session is {s.state}")
    return s


def _touch(s: GuiSession) -> None:
    """Activity keeps a session alive: the clock restarts on every edit."""
    GuiSession.objects.filter(pk=s.pk).update(expires_at=gui.new_expiry())


def _rate_limit(s: GuiSession) -> None:
    from django.conf import settings
    key = f"gui-validate:{s.id}"
    cache.add(key, 0, timeout=60)
    try:
        n = cache.incr(key)
    except ValueError:
        cache.set(key, 1, timeout=60)
        n = 1
    if n > settings.GUI_VALIDATE_RATE_PER_MINUTE:
        raise Throttled(wait=60)


def _body_row(request) -> tuple[str, dict]:
    op = str(request.data.get("op_type", "")).upper()
    if op not in gui.OPS:
        raise ValidationError({"op_type": f"must be one of {list(gui.OPS)}"})
    row = request.data.get("row")
    if not isinstance(row, dict):
        raise ValidationError({"row": "an object of cell values is required"})
    return op, row


def _row_dict(r: GuiRow) -> dict:
    return {"row_key": r.row_key, "op_type": r.op_type, "row": r.raw, "issues": r.issues}


class GuiSessionCreateView(APIView):
    permission_classes = PERMS

    def post(self, request):
        try:
            policy_id = int(request.data.get("policy_id"))
        except (TypeError, ValueError):
            raise ValidationError({"policy_id": "required integer"}) from None
        eff = request.data.get("effective_date")
        try:
            effective = dt.date.fromisoformat(eff) if eff else None
        except ValueError:
            raise ValidationError({"effective_date": "must be YYYY-MM-DD"}) from None
        policy = _policy(request, policy_id)
        if policy.status != "APPROVED_AND_LIVE":
            raise Unprocessable(f"policy is {policy.status}; endorsements need an APPROVED_AND_LIVE policy")
        s = GuiSession.objects.create(
            broker_id=request.user.broker_id, employer_id=policy.employer_id, policy_id=policy_id,
            actor_user_id=request.user.user_id, effective_date=effective, policy_facts=_policy_facts(policy),
            expires_at=gui.new_expiry())
        return Response({"session_id": str(s.id), "state": s.state, "expires_at": s.expires_at, "schema": gui.schema()},
                        status=status.HTTP_201_CREATED)


class GuiSessionDetailView(APIView):
    permission_classes = PERMS

    def get(self, request, session_id):
        s = _session(request, session_id)
        rows = list(s.rows.order_by("id"))
        return Response({"session_id": str(s.id), "state": s.state, "policy_id": s.policy_id, "expires_at": s.expires_at,
                         "row_count": len(rows),
                         "rows_with_errors": sum(any(i["severity"] == "ERROR" for i in r.issues) for r in rows),
                         "rows": [_row_dict(r) for r in rows], "jobs": s.submitted_jobs})


class GuiValidateView(APIView):
    """Synchronous check of one row as the user edits a cell. Stores nothing."""
    permission_classes = PERMS

    def post(self, request, session_id):
        s = _session(request, session_id, mutate=True)
        _rate_limit(s)
        op, row = _body_row(request)
        norm, issues = gui.check_row(s, op, row, row_key=request.data.get("row_key"))
        _touch(s)
        return Response({"valid": not any(i["severity"] == "ERROR" for i in issues), "issues": issues, "normalised": {
            k: v for k, v in norm.items()}})


class GuiRowView(APIView):
    permission_classes = PERMS

    def put(self, request, session_id, row_key):
        s = _session(request, session_id, mutate=True)
        if not row_key or len(row_key) > 64:
            raise ValidationError({"row_key": "1-64 characters"})
        op, row = _body_row(request)
        norm, issues = gui.check_row(s, op, row, row_key=row_key)
        obj, created = GuiRow.objects.update_or_create(session=s, row_key=row_key, defaults={
            "op_type": op, "raw": row, "normalised": norm, "issues": issues,
            "employee_code": norm.get("employee_code") or "", "relation_code": norm.get("relation_code") or ""})
        _touch(s)
        return Response(_row_dict(obj), status=status.HTTP_201_CREATED if created else status.HTTP_200_OK)

    def delete(self, request, session_id, row_key):
        s = _session(request, session_id, mutate=True)
        deleted, _ = GuiRow.objects.filter(session=s, row_key=row_key).delete()
        if not deleted:
            raise NotFound()
        _touch(s)
        return Response(status=status.HTTP_204_NO_CONTENT)


class GuiSubmitView(APIView):
    permission_classes = PERMS

    def post(self, request, session_id):
        s = _session(request, session_id)                    # a repeat submit of a SUBMITTED session is allowed
        if s.state == "ABANDONED":
            raise Conflict("session was abandoned")
        if s.state == "OPEN" and s.expires_at < timezone.now():
            GuiSession.objects.filter(pk=s.pk).update(state="ABANDONED")
            raise Gone("session expired; start a new one")
        try:
            jobs, created = gui.submit(s.id, actor=request.user.user_id, actor_ip=request.META.get("REMOTE_ADDR"))
        except ValueError as e:
            raise Unprocessable({"detail": "some rows need fixing before submit", "rows": e.args[0]}) from e
        base = request.path.split("gui/sessions/")[0]
        body = {"session_id": str(s.id), "jobs": [{**j, "progress_url": f"{base}{j['job_id']}/progress/"} for j in jobs]}
        return Response(body, status=status.HTTP_202_ACCEPTED if created else status.HTTP_200_OK)


class GuiAbandonView(APIView):
    permission_classes = PERMS

    def post(self, request, session_id):
        s = _session(request, session_id, mutate=True)
        GuiSession.objects.filter(pk=s.pk).update(state="ABANDONED")
        return Response({"session_id": str(s.id), "state": "ABANDONED"})
