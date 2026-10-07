from dataclasses import dataclass

from rest_framework_simplejwt.authentication import JWTAuthentication


@dataclass(frozen=True)
class TenantContext:
    """Tenant scope extracted from the verified JWT. Set once per request."""

    user_id: str
    role: str
    broker_id: int | None
    employer_id: int | None
    tpa_id: int | None

    @classmethod
    def from_token(cls, token) -> "TenantContext":
        return cls(
            user_id=str(token["user_id"]),
            role=token["role"],
            broker_id=token.get("broker_id"),
            employer_id=token.get("employer_id"),
            tpa_id=token.get("tpa_id"),
        )


class TenantJWTAuthentication(JWTAuthentication):
    def authenticate(self, request):
        result = super().authenticate(request)
        if result is None:
            return None
        user, token = result
        request.tenant = TenantContext.from_token(token)
        return user, token
