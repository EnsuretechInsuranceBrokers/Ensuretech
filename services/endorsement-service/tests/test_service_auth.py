import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from apps.core import service_auth
from apps.core.service_auth import ServiceAuthError, ServiceTokenProvider


@pytest.fixture
def identity(settings):
    state = {"calls": 0, "status": 200, "ttl": 600, "seen": []}

    class H(BaseHTTPRequestHandler):
        def do_POST(self):
            state["calls"] += 1
            state["seen"].append(self.rfile.read(int(self.headers["Content-Length"])).decode())
            self.send_response(state["status"])
            self.end_headers()
            self.wfile.write(json.dumps({"access_token": f"tok{state['calls']}", "token_type": "Bearer",
                                         "expires_in": state["ttl"]}).encode())
        def log_message(self, *a): pass

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    settings.IDENTITY_TOKEN_URL = f"http://127.0.0.1:{srv.server_port}/t/"
    settings.SERVICE_CLIENT_ID, settings.SERVICE_CLIENT_SECRET = "svc_x", "s3cret"
    yield state
    srv.shutdown()


def test_unconfigured_uses_static_token(settings):
    settings.IDENTITY_TOKEN_URL = ""
    assert service_auth.bearer("static") == "Bearer static"


def test_caches_until_near_expiry_then_refreshes(identity):
    now = [0.0]
    p = ServiceTokenProvider(clock=lambda: now[0])
    assert p.get() == p.get() == "tok1" and identity["calls"] == 1
    assert "client_secret=s3cret" in identity["seen"][0] and "grant_type=client_credentials" in identity["seen"][0]
    now[0] = 600 - 61
    assert p.get() == "tok1"
    now[0] = 600 - 59            # inside the refresh margin
    assert p.get() == "tok2"


def test_concurrent_callers_make_one_request(identity):
    p = ServiceTokenProvider()
    out = []
    ts = [threading.Thread(target=lambda: out.append(p.get())) for _ in range(20)]
    [t.start() for t in ts]; [t.join() for t in ts]
    assert set(out) == {"tok1"} and identity["calls"] == 1


def test_invalidate_only_drops_the_failed_token(identity):
    p = ServiceTokenProvider()
    first = p.get()
    p.invalidate("some-other-token")
    assert p.get() == first
    p.invalidate(first)
    assert p.get() == "tok2"


def test_refusal_and_outage_raise_service_auth_error(identity, settings):
    identity["status"] = 401
    with pytest.raises(ServiceAuthError, match="401"):
        ServiceTokenProvider().get()
    settings.IDENTITY_TOKEN_URL = "http://127.0.0.1:1/t/"
    with pytest.raises(ServiceAuthError):
        ServiceTokenProvider().get()


def test_cd_client_reports_unavailable_when_identity_refuses(identity, settings):
    from apps.computation.cd import CdUnavailable, GrpcCdClient
    pytest.importorskip("apps.computation.gen.billing_cd_pb2")
    identity["status"] = 401
    service_auth.provider.invalidate()
    with pytest.raises(CdUnavailable, match="refused"):
        GrpcCdClient().check(1, 1, 10, "j")
