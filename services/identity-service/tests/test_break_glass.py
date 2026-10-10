import jwt
import pytest
from django.conf import settings
from rest_framework.test import APIClient

from apps.users import service_accounts as sa
from apps.users.models import BreakGlassGrant, Role

URL = "/api/v1/auth/break-glass/"
PASSWORD = "break-glass-test-password"
REASON = "BFF is down, finishing a broker's endorsement"


@pytest.fixture(autouse=True)
def _on(settings):
    settings.BREAK_GLASS_PASSWORD = PASSWORD


@pytest.fixture
def system_token(db):
    account, secret = sa.create_account("operator")
    return sa.issue_token(account)[0]


@pytest.fixture
def broker_admin(make_user):
    return make_user("meera@broker.example", Role.BROKER_ADMIN, broker_id=7)


def ask(token=None, **body):
    c = APIClient()
    if token:
        c.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return c.post(URL, body, format="json")


def claims(token):
    return jwt.decode(token, settings.SIMPLE_JWT["VERIFYING_KEY"], algorithms=["RS256"], options={"verify_aud": False})


def test_a_system_caller_with_the_password_gets_a_short_token_for_one_real_user(system_token, broker_admin):
    r = ask(system_token, break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason=REASON)
    assert r.status_code == 200, r.content
    c = claims(r.data["access_token"])
    assert c["break_glass"] is True and c["role"] == "BROKER_ADMIN" and c["broker_id"] == 7 and c["user_id"] == str(broker_admin.pk)
    assert c["bg_reason"] == REASON and c["bg_by"].startswith("svc_")
    assert c["exp"] - c["iat"] == 15 * 60 and "refresh" not in r.data
    assert BreakGlassGrant.objects.get().outcome == "GRANTED"


def test_the_password_alone_or_the_system_token_alone_is_not_enough(system_token, broker_admin):
    assert ask(None, break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason=REASON).status_code == 403
    assert ask(system_token, break_glass_password="wrong", user_id=str(broker_admin.pk), reason=REASON).status_code == 403
    assert ask(system_token, user_id=str(broker_admin.pk), reason=REASON).status_code == 403
    assert sorted(BreakGlassGrant.objects.values_list("outcome", flat=True)) == ["NOT_SYSTEM", "WRONG_PASSWORD", "WRONG_PASSWORD"]


def test_an_ordinary_user_token_cannot_ask(broker_admin, login):
    client, tokens = login(broker_admin.email)
    assert ask(tokens["access"], break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason=REASON).status_code == 403


def test_a_reason_and_a_real_active_user_are_required(system_token, broker_admin):
    assert ask(system_token, break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason="because").status_code == 400
    assert ask(system_token, break_glass_password=PASSWORD, reason=REASON).status_code == 400
    assert ask(system_token, break_glass_password=PASSWORD, user_id="00000000-0000-4000-8000-000000000000", reason=REASON).status_code == 404
    broker_admin.is_active = False
    broker_admin.save()
    assert ask(system_token, break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason=REASON).status_code == 404


def test_without_a_long_password_the_endpoint_does_not_exist(system_token, broker_admin, settings):
    for off in ("", "short"):
        settings.BREAK_GLASS_PASSWORD = off
        assert ask(system_token, break_glass_password=off, user_id=str(broker_admin.pk), reason=REASON).status_code == 404
    assert BreakGlassGrant.objects.count() == 0


def test_the_lifetime_is_capped_at_an_hour(system_token, broker_admin, settings):
    settings.BREAK_GLASS_MINUTES = 600
    r = ask(system_token, break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason=REASON)
    assert r.data["expires_in"] == 3600


def test_the_core_gateway_guard_lets_a_break_glass_token_in_but_not_an_ordinary_user_token(system_token, broker_admin, settings, login):
    from django.http import HttpResponse
    from django.test import RequestFactory
    from config.gateway import GatewaySecretMiddleware
    bg = ask(system_token, break_glass_password=PASSWORD, user_id=str(broker_admin.pk), reason=REASON).data["access_token"]
    _, tokens = login(broker_admin.email)
    settings.GATEWAY_SECRET = "x" * 40                       # only now: the app's own guard would otherwise refuse the calls above
    guard = GatewaySecretMiddleware(lambda request: HttpResponse("ok"))
    rf = RequestFactory()
    assert guard(rf.get("/api/v1/auth/me/", HTTP_AUTHORIZATION=f"Bearer {bg}")).status_code == 200
    assert guard(rf.get("/api/v1/auth/me/", HTTP_AUTHORIZATION=f"Bearer {tokens['access']}")).status_code == 403
