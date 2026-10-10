"""Break glass: act as one named user straight against the core services, without the BFF.

Who may: a holder of a verified SYSTEM token (super credentials) who also knows BREAK_GLASS_PASSWORD. What they get: a normal access token for ONE
existing active user (that user's real role, broker_id and employer_id, so every tenant rule still applies to it), valid for BREAK_GLASS_MINUTES
(default 15, at most 60), marked `break_glass: true` so the core services' gateway guard lets it in without a BFF signature, and with the operator and
the reason inside it. It has no refresh token. Every attempt, granted or refused, is written to identity_break_glass_grants and logged at WARNING.

Fail closed: with no BREAK_GLASS_PASSWORD of at least 16 characters the endpoint does not exist (404).
"""
import hashlib
import hmac
import logging
import uuid
from datetime import timedelta

from django.conf import settings
from django.contrib.auth import get_user_model
from django.utils import timezone
from rest_framework import status
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.views import APIView
from rest_framework_simplejwt.tokens import AccessToken

from .models import BreakGlassGrant
from .serializers import TenantTokenObtainPairSerializer

log = logging.getLogger("break_glass")
MIN_PASSWORD = 16
HEADERS = {"Cache-Control": "no-store", "Pragma": "no-cache"}


def _same(a: str, b: str) -> bool:
    return hmac.compare_digest(hashlib.sha256(a.encode()).digest(), hashlib.sha256(b.encode()).digest())


class BreakGlassView(APIView):
    permission_classes = [AllowAny]
    authentication_classes: list = []
    throttle_scope = "break_glass"

    def _deny(self, request, outcome, code, client_id="", user_id=None, reason=""):
        BreakGlassGrant.objects.create(outcome=outcome, client_id=client_id, target_user_id=user_id, reason=reason[:300], remote_addr=request.META.get("REMOTE_ADDR", ""))
        log.warning("BREAK-GLASS refused (%s) client=%s user=%s from %s", outcome, client_id or "-", user_id, request.META.get("REMOTE_ADDR"))
        return Response({"error": outcome.lower()}, status=code, headers=HEADERS)

    def post(self, request):
        password = getattr(settings, "BREAK_GLASS_PASSWORD", "") or ""
        if len(password) < MIN_PASSWORD:
            return Response(status=status.HTTP_404_NOT_FOUND)
        parts = request.META.get("HTTP_AUTHORIZATION", "").split()
        client_id = ""
        try:
            if len(parts) != 2 or parts[0].lower() != "bearer":
                raise ValueError
            claims = AccessToken(parts[1])
            if claims.get("role") != "SYSTEM":
                raise ValueError
            client_id = str(claims.get("client_id", ""))
        except Exception:
            return self._deny(request, "NOT_SYSTEM", status.HTTP_403_FORBIDDEN)
        reason = str(request.data.get("reason", "")).strip()
        if not _same(str(request.data.get("break_glass_password", "")), password):
            return self._deny(request, "WRONG_PASSWORD", status.HTTP_403_FORBIDDEN, client_id, reason=reason)
        try:
            user_id = uuid.UUID(str(request.data.get("user_id")))
        except ValueError:
            user_id = None
        if user_id is None or not 10 <= len(reason) <= 300:
            return self._deny(request, "BAD_REQUEST", status.HTTP_400_BAD_REQUEST, client_id, user_id, reason)
        user = get_user_model().objects.filter(pk=user_id, is_active=True).first()
        if user is None:
            return self._deny(request, "NO_SUCH_USER", status.HTTP_404_NOT_FOUND, client_id, user_id, reason)
        minutes = max(1, min(60, int(getattr(settings, "BREAK_GLASS_MINUTES", 15))))
        token = TenantTokenObtainPairSerializer.get_token(user).access_token
        token.set_exp(lifetime=timedelta(minutes=minutes))
        token["break_glass"] = True
        token["bg_by"] = client_id
        token["bg_reason"] = reason
        grant = BreakGlassGrant.objects.create(outcome="GRANTED", client_id=client_id, target_user_id=user.pk, reason=reason, jti=str(token["jti"]),
                                               expires_at=timezone.now() + timedelta(minutes=minutes), remote_addr=request.META.get("REMOTE_ADDR", ""))
        log.warning("BREAK-GLASS granted: %s acts as user %s (%s, broker %s, employer %s) for %d min. grant=%s reason=%r", client_id, user.pk, user.role, user.broker_id, user.employer_id, minutes, grant.id, reason)
        return Response({"access_token": str(token), "token_type": "Bearer", "expires_in": minutes * 60, "acting_as": {"user_id": user.pk, "role": user.role,
                         "broker_id": user.broker_id, "employer_id": user.employer_id}, "grant_id": str(grant.id)}, headers=HEADERS)
