"""Resource-server authentication: verify identity-service RS256 access tokens. No user table, no sessions."""
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import jwt
from django.conf import settings
from rest_framework.authentication import BaseAuthentication, get_authorization_header
from rest_framework.exceptions import AuthenticationFailed

BROKER_ROLES = frozenset({"BROKER_ADMIN", "BROKER_SPECIALIZER", "BROKER_NON_ADMIN"})
EMPLOYER_ROLES = frozenset({"EMPLOYER_ADMIN", "EMPLOYER_NON_ADMIN"})


@dataclass(frozen=True)
class Principal:
    """The authenticated caller. Tenant scope comes only from the verified token."""

    user_id: str
    role: str
    broker_id: int | None
    employer_id: int | None
    tpa_id: int | None

    is_authenticated = True

    @property
    def is_broker(self) -> bool:
        return self.role in BROKER_ROLES

    @property
    def is_employer(self) -> bool:
        return self.role in EMPLOYER_ROLES


@lru_cache(maxsize=4)
def _public_key(path: str) -> str:
    return Path(path).read_text()


def decode_token(token: str) -> dict:
    try:
        claims = jwt.decode(
            token,
            _public_key(str(settings.JWT_PUBLIC_KEY_PATH)),
            algorithms=["RS256"],                       # never accept HS256/none here
            issuer=settings.JWT_ISSUER,
            options={"require": ["exp", "iss"]},
        )
    except jwt.PyJWTError as e:
        raise AuthenticationFailed("Invalid token") from e
    if claims.get("token_type") != "access":            # a refresh token must not work as an access token
        raise AuthenticationFailed("Not an access token")
    if not claims.get("role"):
        raise AuthenticationFailed("Token has no role")
    return claims


def _int(v):
    return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def principal_from_claims(c: dict) -> Principal:
    return Principal(
        user_id=str(c.get("user_id", "")),
        role=c["role"],
        broker_id=_int(c.get("broker_id")),
        employer_id=_int(c.get("employer_id")),
        tpa_id=_int(c.get("tpa_id")),
    )


class JWTTenantAuthentication(BaseAuthentication):
    keyword = b"bearer"

    def authenticate(self, request):
        parts = get_authorization_header(request).split()
        if not parts or parts[0].lower() != self.keyword:
            return None
        if len(parts) != 2:
            raise AuthenticationFailed("Malformed Authorization header")
        return principal_from_claims(decode_token(parts[1].decode())), None

    def authenticate_header(self, request):
        return "Bearer"
