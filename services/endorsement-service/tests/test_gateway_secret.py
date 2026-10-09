import time

import pytest
from django.core.exceptions import ImproperlyConfigured
from django.http import HttpResponse
from django.test import RequestFactory, override_settings

from config.gateway import GatewaySecretMiddleware, sign

SECRET = "s3cret-value-s3cret-value-s3cret-value-12345"
PATH = '/api/v1/broker/endorsements/'


def mw(secret=SECRET, allow=False, **extra):
    with override_settings(GATEWAY_SECRET=secret, GATEWAY_ALLOW_UNGUARDED=allow, **extra):
        return GatewaySecretMiddleware(lambda r: HttpResponse("ok"))


def seal(method="GET", path=PATH, query="", auth="Bearer user-token", ts=None, nonce="n" * 24, secret=SECRET):
    ts = str(int(time.time()) if ts is None else ts)
    return {"HTTP_X_GATEWAY_TIMESTAMP": ts, "HTTP_X_GATEWAY_NONCE": nonce, "HTTP_X_GATEWAY_SIGNATURE": sign(secret.encode(), ts, nonce, method, path, query, auth), "HTTP_AUTHORIZATION": auth}


def call(m, method="GET", path=PATH, query="", **headers):
    rf = getattr(RequestFactory(), method.lower())
    return m(rf(path + ("?" + query if query else ""), **headers)).status_code


def test_a_request_with_no_signature_is_refused_even_with_a_token():
    assert call(mw(), HTTP_AUTHORIZATION="Bearer user-token") == 403
    assert call(mw(), HTTP_X_GATEWAY_SECRET=SECRET, HTTP_AUTHORIZATION="Bearer user-token") == 403          # the secret itself is no longer accepted


def test_a_signature_is_good_for_one_method_path_query_token_and_use():
    m = mw()
    assert call(m, **seal(nonce="a" * 24)) == 200
    assert call(m, **seal(nonce="a" * 24)) == 403                                                          # replay
    assert call(m, method="POST", **seal(method="GET", nonce="b" * 24)) == 403                             # method changed
    assert call(m, path=PATH + "x", **seal(nonce="c" * 24)) == 403                                         # path changed
    assert call(m, query="page=9", **seal(query="page=1", nonce="d" * 24)) == 403                           # query changed
    other = seal(auth="Bearer other-token", nonce="e" * 24)
    sig = seal(nonce="e" * 24)["HTTP_X_GATEWAY_SIGNATURE"]
    assert call(m, **{**other, "HTTP_X_GATEWAY_SIGNATURE": sig}) == 403                                  # someone else's token under a captured signature
    assert call(m, **seal(secret="x" * 40, nonce="f" * 24)) == 403                                         # signed with another secret


def test_old_and_future_requests_are_refused():
    m = mw()
    assert call(m, **seal(ts=int(time.time()) - 120, nonce="g" * 24)) == 403
    assert call(m, **seal(ts=int(time.time()) + 120, nonce="h" * 24)) == 403
    assert call(m, **seal(ts=int(time.time()) - 30, nonce="i" * 24)) == 200


def test_the_service_refuses_to_start_unguarded_unless_told_to():
    with pytest.raises(ImproperlyConfigured):
        mw(secret="")
    with pytest.raises(ImproperlyConfigured):
        mw(secret="short")
    assert call(mw(secret="", allow=True)) == 200                                                           # explicit local-development opt-out


def test_a_super_credential_token_passes_without_the_bff_but_an_ordinary_one_does_not(monkeypatch):
    m = mw()
    monkeypatch.setattr('apps.core.auth.decode_token', lambda tok: {"role": "SYSTEM" if tok == "sys" else "BROKER_ADMIN"} if tok != "bad" else (_ for _ in ()).throw(ValueError("bad")))
    assert call(m, HTTP_AUTHORIZATION="Bearer sys") == 200
    assert call(m, HTTP_AUTHORIZATION="Bearer user") == 403
    assert call(m, HTTP_AUTHORIZATION="Bearer bad") == 403
