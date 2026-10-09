import jwt
import pytest
from django.conf import settings
from django.core.exceptions import ValidationError
from rest_framework.test import APIClient

from apps.users.models import User

from .conftest import PASSWORD


def test_jwt_is_rs256_with_tenant_claims(make_user, login):
    make_user("hr@acme.com", "EMPLOYER_ADMIN", broker_id=7, employer_id=42)
    _, tokens = login("hr@acme.com")
    claims = jwt.decode(tokens["access"], settings.SIMPLE_JWT["VERIFYING_KEY"],
                        algorithms=["RS256"])
    assert (claims["role"], claims["broker_id"], claims["employer_id"]) == ("EMPLOYER_ADMIN", 7, 42)
    assert jwt.get_unverified_header(tokens["access"])["alg"] == "RS256"


def test_bad_password_rejected(make_user):
    make_user("a@b.com", "SYSTEM")
    r = APIClient().post("/api/v1/auth/login/", {"email": "a@b.com", "password": "nope"},
                         format="json")
    assert r.status_code == 401


def test_logout_blacklists_refresh(make_user, login):
    make_user("a@b.com", "SYSTEM")
    c, tokens = login("a@b.com")
    assert c.post("/api/v1/auth/logout/", {"refresh": tokens["refresh"]},
                  format="json").status_code == 204
    r = APIClient().post("/api/v1/auth/refresh/", {"refresh": tokens["refresh"]}, format="json")
    assert r.status_code == 401


def test_role_scope_validation(db):
    with pytest.raises(ValidationError):
        User.objects.create_user("x@y.com", PASSWORD, first_name="a", last_name="b",
                                 role="BROKER_ADMIN")  # missing broker_id
    with pytest.raises(ValidationError):
        User.objects.create_user("s@y.com", PASSWORD, first_name="a", last_name="b",
                                 role="SYSTEM", broker_id=1)


def test_no_self_registration():
    r = APIClient().post("/api/v1/users/", {"email": "evil@x.com"}, format="json")
    assert r.status_code == 401


def test_change_password(make_user, login):
    make_user("a@b.com", "SYSTEM")
    c, _ = login("a@b.com")
    r = c.post("/api/v1/auth/change-password/",
               {"old_password": PASSWORD, "new_password": "An0ther-Str0ng-One!"}, format="json")
    assert r.status_code == 204


def test_broker_admin_can_create_a_tpa_login_only_inside_its_own_broker(make_user, login):
    make_user("ba@b.com", "BROKER_ADMIN", broker_id=7)
    c, _ = login("ba@b.com")
    body = {"email": "tpa@t.com", "first_name": "T", "last_name": "P", "role": "TPA_USER", "password": "Str0ng-Passw0rd!x", "tpa_id": 3, "broker_id": 99}
    r = c.post("/api/v1/users/", body, format="json")
    assert r.status_code == 201, r.content
    u = User.objects.get(email="tpa@t.com")
    assert (u.broker_id, u.tpa_id, u.role) == (7, 3, "TPA_USER")           # the broker in the request is ignored
    r = c.post("/api/v1/users/", {**body, "email": "tpa2@t.com", "tpa_id": None}, format="json")
    assert r.status_code == 400                                              # a TPA login must name its TPA
