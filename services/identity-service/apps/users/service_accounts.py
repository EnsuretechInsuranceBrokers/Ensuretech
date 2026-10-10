"""OAuth2 client-credentials flow for machine callers (the endorsement engine, future workers).

Secrets are 384-bit random, so a fast hash (SHA-256) is sufficient and keeps token issuance cheap; comparison is constant-time.
A service token is an ordinary access token (`role=SYSTEM`, `token_type=access`, RS256, same issuer) so resource servers need no
change. It carries no tenant scope, has no refresh token and cannot be used on identity's own user APIs (no matching user row).
"""
import hashlib
import hmac
import secrets
from datetime import timedelta

from django.conf import settings
from django.db import transaction
from django.utils import timezone
from rest_framework_simplejwt.tokens import AccessToken

from .models import ServiceAccount, ServiceCredential

_DUMMY = hashlib.sha256(b"no-such-client").hexdigest()


def hash_secret(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


def new_secret() -> str:
    return secrets.token_urlsafe(48)


@transaction.atomic
def create_account(name: str, description: str = "") -> tuple[ServiceAccount, str]:
    account = ServiceAccount.objects.create(name=name, description=description, client_id=f"svc_{secrets.token_hex(8)}")
    secret = new_secret()
    ServiceCredential.objects.create(account=account, secret_hash=hash_secret(secret))
    return account, secret


@transaction.atomic
def rotate(account: ServiceAccount, grace_minutes: int = 0) -> str:
    """Issue a new secret. Existing secrets stop working after `grace_minutes` (0 = immediately) so a rollout can overlap."""
    now = timezone.now()
    expiry = now + timedelta(minutes=grace_minutes)
    for c in account.credentials.filter(revoked_at__isnull=True):
        if c.expires_at is None or c.expires_at > expiry:
            c.expires_at = expiry
            c.save(update_fields=["expires_at"])
    secret = new_secret()
    ServiceCredential.objects.create(account=account, secret_hash=hash_secret(secret))
    return secret


def authenticate(client_id: str, client_secret: str) -> ServiceAccount | None:
    """Constant work whether or not the client exists; None for any failure (the caller must not say which)."""
    account = ServiceAccount.objects.filter(client_id=client_id, is_active=True).first()
    now = timezone.now()
    presented = hash_secret(client_secret)
    ok = False
    candidates = [c.secret_hash for c in account.credentials.filter(revoked_at__isnull=True)
                  if c.expires_at is None or c.expires_at > now] if account else [_DUMMY]
    for h in candidates:
        ok |= hmac.compare_digest(h, presented)
    if not (account and ok):
        return None
    ServiceAccount.objects.filter(pk=account.pk).update(last_used_at=now)
    return account


def issue_token(account: ServiceAccount) -> tuple[str, int]:
    token = AccessToken()                      # sets token_type=access, iat, exp, jti and (via the backend) iss
    lifetime = timedelta(minutes=settings.SERVICE_TOKEN_MINUTES)
    token.set_exp(lifetime=lifetime)
    token["user_id"] = f"svc:{account.client_id}"
    token["sub"] = f"svc:{account.client_id}"
    token["role"] = "SYSTEM"
    token["client_id"] = account.client_id
    token["service"] = account.name
    return str(token), int(lifetime.total_seconds())
