from rest_framework.permissions import BasePermission

from .constants import API_PREFIX, GATEWAY_ROLES


def gateway_for(path: str) -> str | None:
    """Only the segment directly after /api/v1/ counts, so /broker/x/admin/ cannot spoof."""
    parts = path.strip("/").split("/")
    prefix = API_PREFIX.split("/")
    if parts[: len(prefix)] != prefix or len(parts) <= len(prefix):
        return None
    return parts[len(prefix)]


class GatewayRolePermission(BasePermission):
    """Fail-closed: a gateway segment with no role mapping is forbidden."""

    def has_permission(self, request, view):
        gateway = gateway_for(request.path)
        allowed = GATEWAY_ROLES.get(gateway)
        return bool(allowed) and request.user.is_authenticated and request.user.role in allowed
