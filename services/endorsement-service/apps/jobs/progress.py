"""Server-Sent Events progress for one job.

* Needs an ASGI server (uvicorn/daphne): under WSGI the stream would hold a worker and buffer.
* Auth is the normal Authorization header (use a fetch-based SSE client; browsers' EventSource cannot send headers and
  putting a token in the URL would leak it into logs).
* Emits an event only when something changed (never faster than the poll interval), a keep-alive comment while idle,
  and closes with `rest` when the job is finished or is waiting for a human; reconnect after acting.
"""
import asyncio
import json
import time

from django.conf import settings
from django.http import JsonResponse, StreamingHttpResponse
from rest_framework.exceptions import AuthenticationFailed

from apps.core.auth import BROKER_ROLES, EMPLOYER_ROLES, decode_token, principal_from_claims
from apps.core.permissions import gateway_for

from .models import EndorsementJob
from .states import State

WAITING_ON_A_HUMAN = {State.VALIDATION_PARTIAL, State.CD_INSUFFICIENT, State.CD_PARTIAL_SUFFICIENT,
                      State.CD_CHECK_FAILED, State.PREMIUM_CALC_FAILED, State.ENDORSEMENT_FAILED,
                      State.MANUAL_INTERVENTION_REQUIRED}
SETTLED_HERE = {State.ENDORSED}      # later states belong to integration-service


def payload(job: EndorsementJob) -> dict:
    units = job.chunks_completed + job.chunks_priced + job.chunks_endorsed
    total = 3 * job.chunk_count
    pct = 100 if State[job.status] in SETTLED_HERE or job.phase == "INTEGRATION" or job.is_terminal and job.category == "SUCCESS" else (
        min(99, int(100 * units / total)) if total else 0)
    return {"job_id": str(job.id), "state": job.status, "status_code": job.status_code, "phase": job.phase,
            "category": job.category, "percent": pct, "total_rows": job.total_rows, "failed_rows": job.failed_rows,
            "chunk_count": job.chunk_count, "chunks_validated": job.chunks_completed, "chunks_priced": job.chunks_priced,
            "chunks_endorsed": job.chunks_endorsed}


def rest_reason(job: EndorsementJob) -> str | None:
    s = State[job.status]
    if job.is_terminal:
        return "terminal"
    if s in SETTLED_HERE:
        return "settled"
    if s in WAITING_ON_A_HUMAN:
        return "waiting_for_human"
    if s == State.VALIDATION_PASSED and not settings.PIPELINE_AUTO_CONTINUE:
        return "waiting_for_human"
    return None


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, default=str)}\n\n"


async def job_progress(request, job_id):
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith("bearer "):
        return JsonResponse({"detail": "Authentication credentials were not provided."}, status=401)
    try:
        who = principal_from_claims(decode_token(header[7:].strip()))
    except AuthenticationFailed:
        return JsonResponse({"detail": "Invalid token"}, status=401)
    gw = gateway_for(request.path)
    if not ((gw == "broker" and who.role in BROKER_ROLES) or (gw == "employer" and who.role in EMPLOYER_ROLES)):
        return JsonResponse({"detail": "Forbidden"}, status=403)
    if who.broker_id is None or (who.role in EMPLOYER_ROLES and who.employer_id is None):
        return JsonResponse({"detail": "Forbidden"}, status=403)
    scope = {"broker_id": who.broker_id, **({"employer_id": who.employer_id} if who.role in EMPLOYER_ROLES else {})}
    if not await EndorsementJob.objects.filter(pk=job_id, **scope).aexists():
        return JsonResponse({"detail": "Not found."}, status=404)       # cross-tenant ids look exactly like missing ones

    async def stream():
        poll, beat, limit = settings.SSE_POLL_SECONDS, settings.SSE_HEARTBEAT_SECONDS, settings.SSE_MAX_SECONDS
        t0 = last_out = time.monotonic()
        last = None
        yield "retry: 2000\n\n"
        while True:
            job = await EndorsementJob.objects.filter(pk=job_id, **scope).afirst()
            if job is None:
                yield _sse("gone", {"job_id": str(job_id)})
                return
            body = payload(job)
            now = time.monotonic()
            if body != last:
                yield _sse("progress", {**body, "elapsed_ms": int((now - t0) * 1000)})
                last, last_out = body, now
            reason = rest_reason(job)
            if reason:
                yield _sse("rest", {"reason": reason, **body})
                return
            if now - t0 > limit:
                yield _sse("timeout", {"reconnect": True})
                return
            if now - last_out >= beat:
                yield ": keepalive\n\n"
                last_out = now
            await asyncio.sleep(poll)

    resp = StreamingHttpResponse(stream(), content_type="text/event-stream")
    resp["Cache-Control"] = "no-cache"
    resp["X-Accel-Buffering"] = "no"                                   # tell nginx not to buffer the stream
    return resp
