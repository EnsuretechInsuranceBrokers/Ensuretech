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
    """Fail closed: /broker/ needs a broker role, /employer/ an employer role, anything else is forbidden."""

    def has_permission(self, request, view):
        user = request.user
        if user is None or not getattr(user, "is_authenticated", False):
            return False
        gw = gateway_for(request.path)
        return (gw == "broker" and user.role in BROKER_ROLES) or (gw == "employer" and user.role in EMPLOYER_ROLES)


def HasRole(*roles):
    """Extra role gate on top of the gateway rule, e.g. permission_classes = [GatewayPermission, HasRole(...)]."""
    allowed = frozenset(roles)

    class _HasRole(BasePermission):
        def has_permission(self, request, view):
            return request.user is not None and request.user.role in allowed

    _HasRole.__name__ = "HasRole_" + "_".join(sorted(allowed))
    return _HasRole


# Who may start, resume and cancel endorsements (HR uploads; brokers operate on behalf of employers).
CAN_SUBMIT = HasRole("BROKER_ADMIN", "BROKER_SPECIALIZER", "EMPLOYER_ADMIN", "EMPLOYER_NON_ADMIN")
CAN_DECIDE = HasRole("BROKER_ADMIN", "BROKER_SPECIALIZER", "EMPLOYER_ADMIN")
