"""Only the BFF may call the browser-facing API.

It sends the shared secret (GATEWAY_SECRET) and a session key, HMAC-SHA256(secret, session id). Anything else is refused before the user's token is looked at.
Service-to-service paths are not covered. A blank secret turns the check off (local development and tests).
"""
import hashlib
import hmac
import logging

from django.conf import settings
from django.http import HttpResponse

log = logging.getLogger(__name__)
GUARDED = ("/api/v1/auth/login/", "/api/v1/auth/refresh/", "/api/v1/auth/logout/", "/api/v1/auth/me/", "/api/v1/auth/change-password/", "/api/v1/users/")


class GatewaySecretMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.secret = (getattr(settings, "GATEWAY_SECRET", "") or "").encode()
        if not self.secret:
            log.warning("GATEWAY_SECRET is not set: calls that bypass the BFF are NOT refused. Set it outside local development.")

    def __call__(self, request):
        if self.secret and request.path.startswith(GUARDED) and not self._ok(request):
            return HttpResponse(status=403)
        return self.get_response(request)

    def _ok(self, request):
        given = request.headers.get("X-Gateway-Secret", "")
        sid = request.headers.get("X-Session-Id", "")
        key = request.headers.get("X-Session-Key", "")
        if not (given and sid and key):
            return False
        expected = hmac.new(self.secret, sid.encode(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(given.encode(), self.secret) and hmac.compare_digest(key.encode(), expected.encode())
