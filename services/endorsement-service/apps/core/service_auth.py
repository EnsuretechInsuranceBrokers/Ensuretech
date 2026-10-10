"""Machine credentials for calls to other services (client-credentials against identity-service).

If IDENTITY_TOKEN_URL + SERVICE_CLIENT_ID/SECRET are set, a SYSTEM access token is fetched, cached per process and refreshed
shortly before it expires (single-flight, so a burst of workers makes one request). Otherwise the static *_GRPC_TOKEN settings
are used as before (dev / tests)."""
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from django.conf import settings

REFRESH_MARGIN_SECONDS = 60


class ServiceAuthError(Exception):
    """identity-service refused or could not be reached; callers treat it like the dependency being unavailable."""


class ServiceTokenProvider:
    def __init__(self, clock=time.monotonic):
        self._lock = threading.Lock()
        self._clock = clock
        self._token = None
        self._expires_at = 0.0

    @staticmethod
    def configured() -> bool:
        return bool(settings.IDENTITY_TOKEN_URL and settings.SERVICE_CLIENT_ID and settings.SERVICE_CLIENT_SECRET)

    def invalidate(self, token=None):
        """Drop the cached token (e.g. a downstream said UNAUTHENTICATED). Only if it is still the one that failed."""
        with self._lock:
            if token is None or token == self._token:
                self._token, self._expires_at = None, 0.0

    def get(self) -> str:
        with self._lock:
            if self._token and self._clock() < self._expires_at - REFRESH_MARGIN_SECONDS:
                return self._token
            self._token, ttl = self._fetch()
            self._expires_at = self._clock() + ttl
            return self._token

    @staticmethod
    def _fetch():
        body = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": settings.SERVICE_CLIENT_ID,
                                       "client_secret": settings.SERVICE_CLIENT_SECRET}).encode()
        req = urllib.request.Request(settings.IDENTITY_TOKEN_URL, data=body, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=settings.IDENTITY_TOKEN_TIMEOUT_SECONDS) as r:
                data = json.load(r)
            return data["access_token"], int(data["expires_in"])
        except urllib.error.HTTPError as e:
            raise ServiceAuthError(f"identity-service refused the service credentials (HTTP {e.code})") from e
        except (OSError, ValueError, KeyError) as e:
            raise ServiceAuthError(f"identity-service token request failed: {e}") from e


provider = ServiceTokenProvider()


def bearer(static_token: str) -> str:
    """Authorization header value for an outgoing call."""
    return f"Bearer {provider.get() if provider.configured() else static_token}"
