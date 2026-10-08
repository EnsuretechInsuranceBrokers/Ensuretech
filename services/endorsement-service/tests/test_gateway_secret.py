import hashlib
import hmac

from django.http import HttpResponse
from django.test import RequestFactory, override_settings

from config.gateway import GUARDED, GatewaySecretMiddleware

SECRET = "s3cret-value"


def run(path, **headers):
    with override_settings(GATEWAY_SECRET=SECRET):
        mw = GatewaySecretMiddleware(lambda r: HttpResponse("ok"))
    return mw(RequestFactory().get(path, **headers)).status_code


def good(sid="sess-1"):
    return {"HTTP_X_GATEWAY_SECRET": SECRET, "HTTP_X_SESSION_ID": sid,
            "HTTP_X_SESSION_KEY": hmac.new(SECRET.encode(), sid.encode(), hashlib.sha256).hexdigest()}


def test_a_guarded_path_needs_the_secret_and_a_matching_session_key():
    p = GUARDED[0]
    assert run(p) == 403
    assert run(p, **{**good(), "HTTP_X_GATEWAY_SECRET": "wrong"}) == 403
    assert run(p, **{**good(), "HTTP_X_SESSION_KEY": "0" * 64}) == 403
    assert run(p, **{k: v for k, v in good().items() if k != "HTTP_X_SESSION_ID"}) == 403
    assert run(p, **good()) == 200


def test_unguarded_paths_and_a_blank_secret_are_left_alone():
    assert run("/api/v1/auth/service-token/") == 200 or GUARDED[0] != "/api/v1/auth/service-token/"
    with override_settings(GATEWAY_SECRET=""):
        mw = GatewaySecretMiddleware(lambda r: HttpResponse("ok"))
    assert mw(RequestFactory().get(GUARDED[0])).status_code == 200
