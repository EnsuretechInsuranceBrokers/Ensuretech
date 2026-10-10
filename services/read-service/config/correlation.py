"""One id for the whole life of a request, in every log line it causes, across services.

The BFF mints an id (or accepts a plain one from the caller) and sends it as X-Correlation-Id. This module keeps it for the request (a context variable, so it follows the request through
sync and async code), puts it in every log line (LOGGING in settings: the %(correlation_id)s field), returns it in the response, writes one access line per request, and hands it on:
to Celery tasks (a message header, so the worker's log lines carry the request's id) and to the other services' gRPC calls (x-correlation-id metadata).
An id that is not plainly an id (wrong characters, too long, newlines) is replaced, never logged.
Grep the id across .dev/logs/*.log (or your log store) to follow one request through every service.
"""
import contextvars
import logging
import re
import time
import uuid

from asgiref.sync import iscoroutinefunction, markcoroutinefunction

HEADER = "X-Correlation-Id"
_VALID = re.compile(r"^[A-Za-z0-9._-]{8,64}$")
_current: contextvars.ContextVar[str] = contextvars.ContextVar("correlation_id", default="")
access = logging.getLogger("access")


def valid(value) -> bool:
    return isinstance(value, str) and bool(_VALID.match(value))


def current() -> str | None:
    """The id of the request (or task) being served, or None."""
    return _current.get() or None


def use(value) -> str:
    """Make this the current id (a fresh one if it is not plainly an id) and return it."""
    cid = value if valid(value) else str(uuid.uuid4())
    _current.set(cid)
    return cid


def clear() -> None:
    _current.set("")


def grpc_metadata() -> tuple:
    """For the metadata= of an outgoing gRPC call."""
    return (("x-correlation-id", current() or str(uuid.uuid4())),)


class CorrelationFilter(logging.Filter):
    """Adds %(correlation_id)s to every record ('-' outside a request)."""

    def filter(self, record):
        record.correlation_id = current() or "-"
        return True


class CorrelationMiddleware:
    """First in MIDDLEWARE, before the gateway lock, so a refused request is logged with its id too. Works under both WSGI and ASGI."""
    sync_capable = True
    async_capable = True

    def __init__(self, get_response):
        self.get_response = get_response
        if iscoroutinefunction(get_response):
            markcoroutinefunction(self)

    def __call__(self, request):
        if iscoroutinefunction(self):
            return self.__acall__(request)
        cid, t0 = use(request.headers.get(HEADER)), time.perf_counter()
        response = self.get_response(request)
        return self._done(request, response, cid, t0)

    async def __acall__(self, request):
        cid, t0 = use(request.headers.get(HEADER)), time.perf_counter()
        response = await self.get_response(request)
        return self._done(request, response, cid, t0)

    @staticmethod
    def _done(request, response, cid, t0):
        response[HEADER] = cid
        access.info("%s %s -> %s %d ms", request.method, request.path, response.status_code, (time.perf_counter() - t0) * 1000)
        return response
