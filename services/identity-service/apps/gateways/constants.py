"""Role-based entry gateways. Path prefix -> roles allowed. Unlisted gateways are DENIED."""
from apps.users.models import Role

GATEWAY_ROLES = {
    "broker": {Role.BROKER_ADMIN, Role.BROKER_SPECIALIZER, Role.BROKER_NON_ADMIN, Role.SYSTEM},
    "employer": {Role.EMPLOYER_ADMIN, Role.EMPLOYER_NON_ADMIN, Role.SYSTEM},
    "tpa": {Role.TPA_USER, Role.SYSTEM},
    "admin": {Role.SYSTEM},
}
API_PREFIX = "api/v1"
