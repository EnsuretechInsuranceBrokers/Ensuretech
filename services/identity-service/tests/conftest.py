import pytest
from rest_framework.test import APIClient

from apps.users.models import User

PASSWORD = "Str0ng-Passw0rd!x"


@pytest.fixture
def make_user(db):
    def _make(email, role, **scope):
        return User.objects.create_user(email, PASSWORD, first_name="T", last_name="U",
                                        role=role, **scope)
    return _make


@pytest.fixture
def login():
    def _login(email):
        c = APIClient()
        r = c.post("/api/v1/auth/login/", {"email": email, "password": PASSWORD}, format="json")
        assert r.status_code == 200, r.content
        c.credentials(HTTP_AUTHORIZATION=f"Bearer {r.data['access']}")
        return c, r.data
    return _login


@pytest.fixture(autouse=True)
def _isolate(settings):
    from django.core.cache import cache
    cache.clear()
    settings.PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]
