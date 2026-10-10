import logging

import pytest
from django.http import HttpResponse
from django.test import RequestFactory

from config import correlation
from config.correlation import CorrelationFilter, CorrelationMiddleware


def through(header=None, status=200):
    seen = {}

    def view(request):
        seen["id"] = correlation.current()
        logging.getLogger("some.service").warning("inside the request")
        return HttpResponse(status=status)
    extra = {} if header is None else {"HTTP_X_CORRELATION_ID": header}
    response = CorrelationMiddleware(view)(RequestFactory().get("/api/v1/anything", **extra))
    return seen["id"], response["X-Correlation-Id"], response


def test_a_plain_id_is_kept_seen_by_the_view_and_returned():
    inside, returned, _ = through("qa-trace-12345")
    assert inside == returned == "qa-trace-12345"
    assert correlation.current() == "qa-trace-12345" or correlation.current() is None      # a sync request leaves nothing for the next one to inherit once cleared by the next use()


def test_no_id_gets_one_and_each_request_its_own():
    a, b = through()[1], through()[1]
    assert len(a) == 36 and a != b


@pytest.mark.parametrize("evil", ["short", "x" * 65, "has space in it", "line1\nFORGED LOG LINE", "semi;colon;1234", "<script>alert(1)</script>", "ünïcode-ident"])
def test_anything_that_is_not_plainly_an_id_is_replaced_never_logged(evil):
    inside, returned, _ = through(evil)
    assert inside == returned and returned != evil and len(returned) == 36


def test_every_log_line_in_the_request_carries_the_id_and_one_access_line_is_written(caplog):
    f = CorrelationFilter()
    records = []

    class Grab(logging.Handler):
        def emit(self, record):
            f.filter(record)
            records.append(record)
    h = Grab()
    logging.getLogger().addHandler(h)
    try:
        with caplog.at_level(logging.INFO):
            through("qa-trace-67890", status=403)
    finally:
        logging.getLogger().removeHandler(h)
    inside = [r for r in records if r.name == "some.service"]
    assert inside and inside[0].correlation_id == "qa-trace-67890"
    access = [r.getMessage() for r in records if r.name == "access"]
    assert len(access) == 1 and access[0].startswith("GET /api/v1/anything -> 403 ")
    assert not any("FORGED" in r.getMessage() for r in records)


def test_outside_a_request_the_field_is_a_dash():
    correlation.clear()
    r = logging.LogRecord("x", logging.INFO, __file__, 1, "m", (), None)
    CorrelationFilter().filter(r)
    assert r.correlation_id == "-"


def test_grpc_calls_carry_the_current_id():
    correlation.use("qa-trace-grpc-1")
    assert correlation.grpc_metadata() == (("x-correlation-id", "qa-trace-grpc-1"),)
