from rest_framework.permissions import BasePermission

from .auth import BROKER_ROLES, EMPLOYER_ROLES

API_PREFIX = ["api", "v1"]


def gateway_for(path: str) -> str | None:
    """Only the segment right after /api/v1/ counts, so /broker/x/employer/ cannot spoof a gateway."""
    parts = path.strip("/").split("/")
    if parts[:2] != API_PREFIX or len(parts) <= 2:
        return None
    return parts[2]


class GatewayPermission(BasePermission):
    """Fail closed: /broker/ needs a broker role (with a broker_id), /employer/ an employer role (with broker_id and
    employer_id). Anything else, including SYSTEM and TPA users, is forbidden: this service has no machine or TPA readers."""

    def has_permission(self, request, view):
        u = request.user
        if u is None or not getattr(u, "is_authenticated", False):
            return False
        gw = gateway_for(request.path)
        if gw == "broker":
            return u.role in BROKER_ROLES and u.broker_id is not None
        if gw == "employer":
            return u.role in EMPLOYER_ROLES and u.broker_id is not None and u.employer_id is not None
        return False


class IsBrokerAdmin(BasePermission):
    def has_permission(self, request, view):
        return request.user is not None and getattr(request.user, "role", None) == "BROKER_ADMIN"
