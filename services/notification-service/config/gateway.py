"""Nothing reaches this service except through the BFF.

Every request must carry a valid signature from the BFF (or from a core service holding the same secret):
    X-Gateway-Timestamp  unix seconds
    X-Gateway-Nonce      one-time value
    X-Gateway-Signature  hex HMAC-SHA256(secret, "v1\n" ts "\n" nonce "\n" METHOD "\n" path "\n" query "\n" sha256hex(Authorization header))
so a signature is good for one method, path, query and user token, for 60 seconds, once. The secret itself never travels.
The only other way in is a verified SYSTEM token: a super credential (a service account whose secret identity-service stores hashed)
signs in for a token. A request with neither is refused with an empty 403 before authentication, parsing or any database work.

Fail closed: with no GATEWAY_SECRET the service refuses to start, unless GATEWAY_ALLOW_UNGUARDED=true (local development; tests set it).
The same function lives in the BFF (GatewaySeal.php) and the Spring services (GatewaySeal.java): change them together.
"""
import hashlib
import hmac
import logging
import threading
import time

from django.conf import settings
from django.core.exceptions import ImproperlyConfigured
from django.http import HttpResponse

log = logging.getLogger(__name__)
VERSION = "v1"
WINDOW_SECONDS = 60
MAX_NONCES = 200_000
OPEN = ()


def canonical(ts, nonce, method, path, query, authorization):
    return f"{VERSION}\n{ts}\n{nonce}\n{method.upper()}\n{path}\n{query}\n" + hashlib.sha256((authorization or "").encode()).hexdigest()


def sign(secret: bytes, ts, nonce, method, path, query, authorization) -> str:
    return hmac.new(secret, canonical(ts, nonce, method, path, query, authorization).encode(), hashlib.sha256).hexdigest()


class GatewaySecretMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.secret = (getattr(settings, "GATEWAY_SECRET", "") or "").encode()
        allow = bool(getattr(settings, "GATEWAY_ALLOW_UNGUARDED", False))
        if not self.secret and not allow:
            raise ImproperlyConfigured("GATEWAY_SECRET is not set, so this service would answer anyone who can reach it. Set it (the BFF's BFF_GATEWAY_SECRET); "
                                       "GATEWAY_ALLOW_UNGUARDED=true is for local development only.")
        if self.secret and len(self.secret) < 32 and not allow:
            raise ImproperlyConfigured("GATEWAY_SECRET is shorter than 32 characters. Use a long random value (openssl rand -hex 32).")
        if not self.secret:
            log.warning("GATEWAY_SECRET is not set and GATEWAY_ALLOW_UNGUARDED is on: requests that bypass the BFF are NOT refused. Local development only.")
        self.dev_tools = bool(getattr(settings, "DEV_TOOLS", False)) and bool(getattr(settings, "DEBUG", False))
        self.open_cidrs = getattr(settings, "GATEWAY_OPEN_CIDRS", "") or ""
        self._seen: dict[str, float] = {}
        self._lock = threading.Lock()

    def __call__(self, request):
        if self.secret and self._open(request) and not self._network_ok(request):
            return HttpResponse(status=403)
        if self.secret and not self._open(request) and not self._signed(request) and not self._system(request):
            log.info("refused a request that did not come through the BFF: %s %s from %s", request.method, request.path, request.META.get("REMOTE_ADDR"))
            return HttpResponse(status=403)
        return self.get_response(request)

    def _network_ok(self, request):
        """Optional: only these networks may even knock on an open door (GATEWAY_OPEN_CIDRS, comma separated). Blank = anyone who can reach the port."""
        cidrs = self.open_cidrs
        if not cidrs.strip():
            return True
        import ipaddress
        try:
            ip = ipaddress.ip_address(request.META.get("REMOTE_ADDR", ""))
            return any(ip in ipaddress.ip_network(c.strip(), strict=False) for c in cidrs.split(",") if c.strip())
        except ValueError:
            return False

    def _open(self, request):
        if self.dev_tools and request.path.startswith(("/silk/", "/__debug__/", "/static/")):
            return True                       # DEV_TOOLS (DEBUG only): the debugging dashboards are used from a browser, which cannot sign
        return request.path in OPEN or request.path.startswith(tuple(p for p in OPEN if p.endswith("/")))

    def _signed(self, request):
        h = request.headers
        ts, nonce, sig = h.get("X-Gateway-Timestamp", ""), h.get("X-Gateway-Nonce", ""), h.get("X-Gateway-Signature", "")
        if not (ts.isdigit() and len(ts) <= 12 and 16 <= len(nonce) <= 64 and sig):
            return False
        now = time.time()
        if abs(now - int(ts)) > WINDOW_SECONDS:
            return False
        expected = sign(self.secret, ts, nonce, request.method, request.path, request.META.get("QUERY_STRING", ""), h.get("Authorization", ""))
        if not hmac.compare_digest(expected.encode(), sig.encode()):
            return False
        return self._remember(nonce, now)

    def _remember(self, nonce, now):
        """Only after the signature checks out, so strangers cannot fill the cache. A full cache refuses rather than forgets: a replay must never get through."""
        with self._lock:
            if len(self._seen) >= MAX_NONCES:
                for k in [k for k, t in self._seen.items() if now - t > WINDOW_SECONDS * 2]:
                    del self._seen[k]
                if len(self._seen) >= MAX_NONCES:
                    return False
            if nonce in self._seen:
                return False
            self._seen[nonce] = now
            return True

    def _system(self, request):
        """A super-credential call: a token identity-service issued to a service account (role SYSTEM), verified like any other."""
        parts = request.headers.get("Authorization", "").split()
        if len(parts) != 2 or parts[0].lower() != "bearer":
            return False
        try:
            from apps.core.auth import decode_token
            return decode_token(parts[1]).get("role") == "SYSTEM"
        except Exception:                                           # any failure to verify is "no"
            return False
