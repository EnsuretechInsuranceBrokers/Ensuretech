import pytest

CASES = [
    ("BROKER_ADMIN", {"broker_id": 1}, "broker", 200),
    ("BROKER_ADMIN", {"broker_id": 1}, "employer", 403),
    ("BROKER_ADMIN", {"broker_id": 1}, "admin", 403),
    ("EMPLOYER_NON_ADMIN", {"broker_id": 1, "employer_id": 2}, "employer", 200),
    ("EMPLOYER_NON_ADMIN", {"broker_id": 1, "employer_id": 2}, "broker", 403),
    ("TPA_USER", {"tpa_id": 1}, "tpa", 200),
    ("TPA_USER", {"tpa_id": 1}, "broker", 403),
    ("SYSTEM", {}, "admin", 200),
    ("SYSTEM", {}, "broker", 200),
]


@pytest.mark.parametrize("role,scope,gateway,expected", CASES)
def test_gateway_matrix(make_user, login, role, scope, gateway, expected):
    make_user("u@x.com", role, **scope)
    c, _ = login("u@x.com")
    assert c.get(f"/api/v1/{gateway}/whoami/").status_code == expected


def test_whoami_returns_tenant_claims(make_user, login):
    make_user("u@x.com", "EMPLOYER_ADMIN", broker_id=3, employer_id=9)
    c, _ = login("u@x.com")
    body = c.get("/api/v1/employer/whoami/").json()
    assert body["broker_id"] == 3 and body["employer_id"] == 9


def test_unauthenticated_is_401():
    from rest_framework.test import APIClient
    assert APIClient().get("/api/v1/broker/whoami/").status_code == 401


def test_gateway_segment_cannot_be_spoofed():
    from apps.gateways.permissions import gateway_for
    assert gateway_for("/api/v1/broker/admin/x/") == "broker"
    assert gateway_for("/api/v1/unknown/") == "unknown"  # no role mapping -> denied
    assert gateway_for("/other/broker/") is None


def test_unmapped_gateway_denied(make_user, login):
    from apps.gateways.permissions import GatewayRolePermission
    from types import SimpleNamespace
    req = SimpleNamespace(path="/api/v1/unknown/x/", user=SimpleNamespace(is_authenticated=True,
                                                                          role="SYSTEM"))
    assert GatewayRolePermission().has_permission(req, None) is False
