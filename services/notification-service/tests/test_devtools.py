import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from django.core.exceptions import ImproperlyConfigured
from django.http import HttpResponse
from django.test import RequestFactory, override_settings

from config import devtools
from config.gateway import GatewaySecretMiddleware

SECRET = "s3cret-value-s3cret-value-s3cret-value-12345"


def test_nothing_is_on_by_default():
    from django.conf import settings
    assert settings.DEV_TOOLS is False
    assert "silk" not in settings.INSTALLED_APPS and "debug_toolbar" not in settings.INSTALLED_APPS
    assert devtools.urlpatterns() == []


def test_dev_tools_refuse_to_switch_on_without_debug():
    with pytest.raises(ImproperlyConfigured):
        devtools.configure({"DEV_TOOLS": True, "DEBUG": False})
    devtools.configure({"DEV_TOOLS": False, "DEBUG": False})          # off: does nothing


def test_the_dashboards_are_open_past_the_lock_only_while_dev_tools_are_on():
    def status(path, **flags):
        with override_settings(GATEWAY_SECRET=SECRET, GATEWAY_ALLOW_UNGUARDED=False, **flags):
            mw = GatewaySecretMiddleware(lambda r: HttpResponse("ok"))
        return mw(RequestFactory().get(path)).status_code
    assert status("/silk/") == 403 and status("/__debug__/") == 403
    assert status("/silk/", DEV_TOOLS=True, DEBUG=False) == 403                       # DEBUG off: still locked
    assert status("/silk/", DEV_TOOLS=True, DEBUG=True) == 200
    assert status("/api/v1/anything", DEV_TOOLS=True, DEBUG=True) == 403              # nothing else is opened


def test_ray_sends_the_wire_format_ray_reads_and_is_silent_without_the_app(monkeypatch):
    got = []

    class H(BaseHTTPRequestHandler):
        def do_GET(self): self.send_response(404); self.end_headers()

        def do_POST(self):
            got.append(json.loads(self.rfile.read(int(self.headers["Content-Length"])))); self.send_response(200); self.end_headers()

        def log_message(self, *a): pass
    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    monkeypatch.setattr(devtools, "RAY_URL", f"http://127.0.0.1:{srv.server_port}/")
    devtools._available["at"] = 0
    devtools.ray("hello", {"a": 1}).label("check").green()
    srv.shutdown()
    types = [p["type"] for body in got for p in body["payloads"]]
    assert types == ["log", "label", "color"]
    assert len({body["uuid"] for body in got}) == 1                                    # one Ray entry: log, label and colour share a uuid
    log = got[0]["payloads"][0]
    assert log["content"]["values"] == ["hello", {"a": 1}] and {"file", "line_number", "hostname"} <= set(log["origin"])
    # no Ray running: a silent no-op, never an error
    monkeypatch.setattr(devtools, "RAY_URL", "http://127.0.0.1:1/")
    devtools._available["at"] = 0
    devtools.ray("nobody is listening").red()
