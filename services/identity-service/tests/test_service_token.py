import base64
from datetime import timedelta

import jwt
import pytest
from django.conf import settings
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from apps.users import service_accounts as sa
from apps.users.models import ServiceCredential

URL = "/api/v1/auth/service-token/"


def grant(client_id, secret, **extra):
    return APIClient().post(URL, {"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret, **extra},
                            format="json")


@pytest.fixture
def svc(db):
    return sa.create_account("endorsement-engine")


def test_issues_system_access_token_resource_servers_accept(svc):
    acct, secret = svc
    r = grant(acct.client_id, secret)
    assert r.status_code == 200 and r.data["token_type"] == "Bearer" and r.data["expires_in"] == 600
    assert r["Cache-Control"] == "no-store"
    c = jwt.decode(r.data["access_token"], settings.SIMPLE_JWT["VERIFYING_KEY"], algorithms=["RS256"], issuer="ensuretech-identity")
    assert (c["role"], c["token_type"], c["service"]) == ("SYSTEM", "access", "endorsement-engine")
    assert c["sub"] == c["user_id"] == f"svc:{acct.client_id}"
    assert not any(k in c for k in ("broker_id", "employer_id", "tpa_id"))
    assert "refresh_token" not in r.data


def test_wrong_secret_unknown_client_and_inactive_are_indistinguishable(svc):
    acct, secret = svc
    bodies = {grant(acct.client_id, "nope").status_code, grant("svc_unknown", secret).status_code}
    assert bodies == {401}
    assert grant(acct.client_id, "nope").data == grant("svc_unknown", "nope").data == {"error": "invalid_client"}
    acct.is_active = False
    acct.save()
    assert grant(acct.client_id, secret).status_code == 401


def test_basic_auth_and_grant_type_validation(svc):
    acct, secret = svc
    b = base64.b64encode(f"{acct.client_id}:{secret}".encode()).decode()
    r = APIClient().post(URL, {"grant_type": "client_credentials"}, format="json", HTTP_AUTHORIZATION=f"Basic {b}")
    assert r.status_code == 200
    assert grant(acct.client_id, secret, grant_type="password").data == {"error": "unsupported_grant_type"}
    assert APIClient().post(URL, {"grant_type": "client_credentials"}, format="json", HTTP_AUTHORIZATION="Basic !!!").status_code == 401
    assert APIClient().post(URL, {"grant_type": "client_credentials"}, format="json").status_code == 401


def test_secret_is_stored_hashed_only(svc):
    acct, secret = svc
    assert secret not in ServiceCredential.objects.get(account=acct).secret_hash


def test_rotation_with_grace_then_expiry(svc):
    acct, old = svc
    new = sa.rotate(acct, grace_minutes=30)
    assert grant(acct.client_id, old).status_code == 200 and grant(acct.client_id, new).status_code == 200
    ServiceCredential.objects.filter(expires_at__isnull=False).update(expires_at=timezone.now() - timedelta(seconds=1))
    assert grant(acct.client_id, old).status_code == 401 and grant(acct.client_id, new).status_code == 200


def test_rotation_without_grace_cuts_old_immediately(svc):
    acct, old = svc
    new = sa.rotate(acct)
    assert grant(acct.client_id, old).status_code == 401 and grant(acct.client_id, new).status_code == 200


def test_revoke_command_and_last_used(svc, capsys):
    acct, secret = svc
    assert grant(acct.client_id, secret).status_code == 200
    acct.refresh_from_db()
    assert acct.last_used_at is not None
    call_command("service_account", "revoke", "endorsement-engine")
    assert grant(acct.client_id, secret).status_code == 401


def test_create_command_prints_working_credentials(db, capsys):
    call_command("service_account", "create", "billing-worker")
    out = dict(line.split("=", 1) for line in capsys.readouterr().out.splitlines() if line.startswith("SERVICE_"))
    assert grant(out["SERVICE_CLIENT_ID"], out["SERVICE_CLIENT_SECRET"]).status_code == 200


def test_service_token_is_useless_on_identity_user_apis(svc):
    acct, secret = svc
    c = APIClient()
    c.credentials(HTTP_AUTHORIZATION=f"Bearer {grant(acct.client_id, secret).data['access_token']}")
    assert c.get("/api/v1/auth/me/").status_code == 401
    assert c.post("/api/v1/users/", {"email": "x@y.com"}, format="json").status_code == 401


def test_throttled_independently_of_user_login(svc, settings):
    settings.REST_FRAMEWORK = {**settings.REST_FRAMEWORK, "DEFAULT_THROTTLE_RATES": {"auth": "10/min", "service_auth": "3/min"}}
    from rest_framework.throttling import ScopedRateThrottle
    ScopedRateThrottle.THROTTLE_RATES = settings.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
    acct, _ = svc
    codes = [grant(acct.client_id, "bad").status_code for _ in range(5)]
    assert codes[:3] == [401] * 3 and 429 in codes[3:]
