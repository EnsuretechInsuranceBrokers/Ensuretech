"""How many employees? decides how they are entered.

Up to GRID_MAX_EMPLOYEES (100): a live grid, checked as a whole on every pause in typing, saved as a session and submitted.
More than that: a spreadsheet sized for the policy, uploaded through the chunked file path.
"""
import datetime as dt
import math

from django.conf import settings
from django.core.cache import cache
from django.db import transaction
from django.http import HttpResponse
from rest_framework import status
from rest_framework.exceptions import Throttled, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.exceptions import Unprocessable
from apps.core.permissions import CAN_SUBMIT, GatewayPermission

from . import gui, template
from .grid import analyse
from .gui_views import _policy, _policy_facts, _session, _touch
from .models import GuiRow, GuiSession
from .normalize import normalize_row

PERMS = [GatewayPermission, CAN_SUBMIT]
XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def _op(value) -> str:
    op = str(value or "").upper()
    if op not in gui.OPS:
        raise ValidationError({"op_type": f"must be one of {list(gui.OPS)}"})
    return op


def _int(value, name, lo, hi) -> int:
    try:
        n = int(value)
    except (TypeError, ValueError):
        raise ValidationError({name: "a whole number is required"}) from None
    if not lo <= n <= hi:
        raise ValidationError({name: f"must be between {lo} and {hi}"})
    return n


def _date(value, name):
    if not value:
        return None
    try:
        return dt.date.fromisoformat(str(value))
    except ValueError:
        raise ValidationError({name: "must be YYYY-MM-DD"}) from None


def _live_policy(request, policy_id):
    policy = _policy(request, policy_id)
    if policy.status != "APPROVED_AND_LIVE":
        raise Unprocessable(f"policy is {policy.status}; endorsements need an APPROVED_AND_LIVE policy")
    return policy


def _grid_throttle(session: GuiSession) -> None:
    key = f"grid-validate:{session.id}"
    cache.add(key, 0, timeout=60)
    try:
        n = cache.incr(key)
    except ValueError:
        cache.set(key, 1, timeout=60)
        n = 1
    if n > settings.GRID_VALIDATE_RATE_PER_MINUTE:
        raise Throttled(wait=60)


class IntakePlanView(APIView):
    """POST {policy_id, op_type, employee_count, effective_date?} -> which way to enter them, and what is needed to start."""
    permission_classes = PERMS

    def post(self, request):
        policy_id = _int(request.data.get("policy_id"), "policy_id", 1, 2**62)
        op = _op(request.data.get("op_type"))
        count = _int(request.data.get("employee_count"), "employee_count", 1, settings.MAX_UPLOAD_ROWS)
        effective = _date(request.data.get("effective_date"), "effective_date")
        policy = _live_policy(request, policy_id)
        threshold = settings.GRID_MAX_EMPLOYEES
        base = {"employee_count": count, "op_type": op, "grid_max_employees": threshold, "policy_id": policy_id}
        if count <= threshold:
            cap = count * settings.GRID_ROWS_PER_EMPLOYEE
            s = GuiSession.objects.create(
                broker_id=request.user.broker_id, employer_id=policy.employer_id, policy_id=policy_id, actor_user_id=request.user.user_id,
                effective_date=effective, expected_employees=count, policy_facts=_policy_facts(policy), expires_at=gui.new_expiry())
            return Response({**base, "mode": "GRID", "session_id": str(s.id), "expires_at": s.expires_at, "schema": gui.schema(),
                             "grid": {"initial_rows": count, "row_cap": cap, "validate": "live"}, "file": None}, status=status.HTTP_201_CREATED)
        rows = count
        return Response({**base, "mode": "FILE", "session_id": None, "schema": None, "grid": None,
                         "file": {"template_rows": min(rows, template.MAX_TEMPLATE_ROWS), "chunk_rows": settings.CHUNK_TARGET_ROWS,
                                  "estimated_chunks": math.ceil(rows / settings.CHUNK_TARGET_ROWS), "max_rows": settings.MAX_UPLOAD_ROWS,
                                  "formats": ["xlsx", "csv"]}}, status=status.HTTP_200_OK)


class IntakeTemplateView(APIView):
    """GET ?policy_id&op_type&employee_count[&effective_date] -> the spreadsheet for large endorsements."""
    permission_classes = PERMS

    def get(self, request):
        policy_id = _int(request.query_params.get("policy_id"), "policy_id", 1, 2**62)
        op = _op(request.query_params.get("op_type"))
        count = _int(request.query_params.get("employee_count"), "employee_count", 1, settings.MAX_UPLOAD_ROWS)
        effective = _date(request.query_params.get("effective_date"), "effective_date")
        policy = _live_policy(request, policy_id)
        body = template.build(op, count, effective_date=effective.isoformat() if effective else None, policy_number=policy.policy_number)
        resp = HttpResponse(body, content_type=XLSX)
        resp["Content-Disposition"] = f'attachment; filename="endorsement-{op.lower()}-{policy.policy_number or policy_id}.xlsx"'
        return resp


def _grid_body(request, s: GuiSession) -> tuple[str, list[dict]]:
    op = _op(request.data.get("op_type"))
    rows = request.data.get("rows")
    if not isinstance(rows, list) or any(not isinstance(r, dict) for r in rows):
        raise ValidationError({"rows": "a list of row objects is required"})
    cap = (s.expected_employees or settings.GRID_MAX_EMPLOYEES) * settings.GRID_ROWS_PER_EMPLOYEE
    if len(rows) > cap:
        raise Unprocessable(f"the grid holds at most {cap} rows for {s.expected_employees} employees; use the spreadsheet for more")
    return op, rows


class GridValidateView(APIView):
    """The whole grid, checked in one pass. Stores nothing. Meant to be called on every pause in typing."""
    permission_classes = PERMS

    def post(self, request, session_id):
        s = _session(request, session_id, mutate=True)
        _grid_throttle(s)
        op, rows = _grid_body(request, s)
        result = analyse(s, op, rows, families=request.data.get("families", True) is not False)
        _touch(s)
        return Response(result)


class GridSaveView(APIView):
    """Replace what is staged for this operation with the grid as it is now (errors included, so nothing typed is lost), then it can be submitted."""
    permission_classes = PERMS

    def put(self, request, session_id):
        s = _session(request, session_id, mutate=True)
        op, rows = _grid_body(request, s)
        result = analyse(s, op, rows)
        by_pos = {r["index"]: r for r in result["rows"]}
        with transaction.atomic():
            GuiRow.objects.filter(session=s, op_type=op).delete()
            keep = []
            for pos, raw in enumerate(rows):
                if by_pos[pos]["blank"]:
                    continue
                cells = {k: v for k, v in raw.items() if k != "row_key"}
                norm, _ = normalize_row(cells, default_effective_date=s.effective_date.isoformat() if s.effective_date else None)
                keep.append(GuiRow(session=s, row_key=f"g{pos}", op_type=op, raw=cells, normalised=norm, issues=by_pos[pos]["issues"],
                                   employee_code=norm.get("employee_code") or "", relation_code=norm.get("relation_code") or ""))
            GuiRow.objects.bulk_create(keep)
        _touch(s)
        result["saved_rows"] = len(keep)
        return Response(result)
