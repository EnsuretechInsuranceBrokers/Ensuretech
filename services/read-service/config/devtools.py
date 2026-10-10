"""Development-only debugging tools for a Django service: django-silk, Django Debug Toolbar and Ray.

Off unless DEV_TOOLS=true, and DEV_TOOLS refuses to switch on when DEBUG is false: they record requests and queries and open pages that skip the BFF's lock
(the dashboards are used from a browser), so they belong on a laptop only. Install the packages with  python ../../scripts/install_python_deps.py --devtools

  Silk              http://127.0.0.1:PORT/silk/        every request this service handled, its SQL, how long each query took, optional profiling.
                                                       Stored in a throwaway SQLite file (.dev/devtools-NAME.sqlite3), never in the service's own database.
                                                       Authorization headers, cookies, passwords, tokens and secrets are masked; the sign-in endpoints are not recorded at all.
  Debug Toolbar     a panel injected into HTML responses (the browsable API, Silk's pages). JSON responses carry no HTML, so use Silk for the API itself.
  Ray               from config.devtools import ray;  ray(value, "text").label("here").green()   and, with RAY_QUERIES=true, every SQL query is sent to Ray with its time.
                    Ray is a desktop app (myray.app, listens on 127.0.0.1:23517); with no Ray running every call is a no-op.
"""
import json
import os
import socket
import sys
import time
import urllib.request
import uuid
from pathlib import Path

RAY_URL = os.environ.get("RAY_URL", "http://127.0.0.1:23517/")
DEV_PATHS = ("/silk/", "/__debug__/", "/static/")           # opened past the gateway lock only while DEV_TOOLS is on


class DevToolsRouter:
    """Silk's tables live in the devtools SQLite file, so the service's real database never gets a silk_* table."""
    apps = {"silk"}

    def db_for_read(self, model, **hints):
        return "devtools" if model._meta.app_label in self.apps else None

    db_for_write = db_for_read

    def allow_relation(self, a, b, **hints):
        return True if {a._meta.app_label, b._meta.app_label} & self.apps else None

    def allow_migrate(self, db, app_label, model_name=None, **hints):
        if app_label in self.apps:
            return db == "devtools"
        if db == "devtools":
            return app_label in {"auth", "contenttypes"}
        return None


def configure(g: dict) -> None:
    """Called at the end of settings.py with its globals()."""
    if not g.get("DEV_TOOLS"):
        return
    from django.core.exceptions import ImproperlyConfigured
    if not g.get("DEBUG"):
        raise ImproperlyConfigured("DEV_TOOLS=true needs DEBUG=true. These tools are for a developer's laptop and are never switched on for a server.")
    base = Path(g.get("BASE_DIR") or Path(__file__).resolve().parent.parent)
    have = {}
    for mod in ("silk", "debug_toolbar"):
        try:
            __import__(mod)
            have[mod] = True
        except ImportError:
            print(f"DEV_TOOLS: {mod} is not installed, skipping it (python ../../scripts/install_python_deps.py --devtools)", file=sys.stderr)
    g["DEV_TOOLS_ACTIVE"] = sorted(have)
    apps, mw = list(g["INSTALLED_APPS"]), list(g["MIDDLEWARE"])
    for needed in ("django.contrib.contenttypes", "django.contrib.auth", "django.contrib.staticfiles"):
        if needed not in apps:
            apps.append(needed)
    g.setdefault("STATIC_URL", "static/")
    if "STATIC_URL" in g and not g["STATIC_URL"]:
        g["STATIC_URL"] = "static/"
    if not g.get("TEMPLATES"):
        g["TEMPLATES"] = [{"BACKEND": "django.template.backends.django.DjangoTemplates", "DIRS": [], "APP_DIRS": True,
                           "OPTIONS": {"context_processors": ["django.template.context_processors.request"]}}]
    else:
        g["TEMPLATES"][0]["APP_DIRS"] = True
        cps = g["TEMPLATES"][0].setdefault("OPTIONS", {}).setdefault("context_processors", [])
        if "django.template.context_processors.request" not in cps:
            cps.append("django.template.context_processors.request")
    if have.get("debug_toolbar"):
        apps.append("debug_toolbar")
        mw.insert(1, "debug_toolbar.middleware.DebugToolbarMiddleware")
        g["INTERNAL_IPS"] = ["127.0.0.1"]
    if have.get("silk"):
        apps.append("silk")
        mw.append("silk.middleware.SilkyMiddleware")
        g.update(
            SILKY_AUTHENTICATION=False, SILKY_META=True, SILKY_MAX_RECORDED_REQUESTS=2000, SILKY_MAX_REQUEST_BODY_SIZE=64, SILKY_MAX_RESPONSE_BODY_SIZE=64,
            SILKY_SENSITIVE_KEYS={"username", "api", "token", "key", "secret", "password", "signature", "authorization", "access", "refresh", "client_secret", "nonce"},
            SILKY_IGNORE_PATHS=["/api/v1/auth/login/", "/api/v1/auth/refresh/", "/api/v1/auth/logout/", "/api/v1/auth/service-token/", "/api/v1/auth/change-password/", "/api/v1/users/"],
        )
    if os.environ.get("RAY_QUERIES", "").lower() in ("1", "true", "yes"):
        mw.append("config.devtools.RayQueriesMiddleware")
    g["INSTALLED_APPS"], g["MIDDLEWARE"] = apps, mw
    # Silk's tables go to a throwaway SQLite file, not the service's database
    root = Path(os.environ.get("DEVTOOLS_DB_DIR") or base.parent.parent / ".dev")
    root.mkdir(parents=True, exist_ok=True)
    name = base.name.replace("-service", "")
    dbs = g.setdefault("DATABASES", {})
    dbs["devtools"] = {"ENGINE": "django.db.backends.sqlite3", "NAME": str(root / f"devtools-{name}.sqlite3")}
    if "default" not in dbs or not dbs["default"].get("ENGINE"):          # read-service has no relational database: give Django one
        dbs["default"] = dict(dbs["devtools"])
    g["DATABASE_ROUTERS"] = ["config.devtools.DevToolsRouter", *g.get("DATABASE_ROUTERS", [])]


def urlpatterns() -> list:
    """Append to urls.py:  urlpatterns += devtools.urlpatterns()"""
    from django.conf import settings
    if not getattr(settings, "DEV_TOOLS", False):
        return []
    from django.urls import include, path
    out = []
    if "silk" in getattr(settings, "DEV_TOOLS_ACTIVE", []):
        out.append(path("silk/", include("silk.urls", namespace="silk")))
    if "debug_toolbar" in getattr(settings, "DEV_TOOLS_ACTIVE", []):
        out.append(path("__debug__/", include("debug_toolbar.urls")))
    from django.contrib.staticfiles.urls import staticfiles_urlpatterns        # so the dashboards are styled under uvicorn too
    return out + staticfiles_urlpatterns()


# ── Ray: the wire format Spatie's own clients use (POST /, {"uuid", "payloads": [...], "meta": {...}}) ────────────────────────────────────────
_available = {"at": 0.0, "ok": False}


def _ray_up() -> bool:
    now = time.monotonic()
    if now - _available["at"] > 30:
        _available["at"] = now
        try:
            host = RAY_URL.split("//", 1)[1].split("/", 1)[0]
            h, _, p = host.partition(":")
            socket.create_connection((h, int(p or 80)), timeout=0.2).close()
            _available["ok"] = True
        except OSError:
            _available["ok"] = False
    return _available["ok"]


def _origin(depth: int = 2) -> dict:
    f = sys._getframe(depth)
    return {"function_name": f.f_code.co_name, "file": f.f_code.co_filename, "line_number": f.f_lineno, "hostname": socket.gethostname()}


def _post(uid: str, payloads: list) -> None:
    if not _ray_up():
        return
    body = json.dumps({"uuid": uid, "payloads": payloads, "meta": {"python_version": sys.version.split()[0], "project_name": "ensuretech"}}, default=str).encode()
    try:
        urllib.request.urlopen(urllib.request.Request(RAY_URL, data=body, headers={"Content-Type": "application/json"}), timeout=0.5).close()
    except OSError:
        _available["ok"] = False


class _Ray:
    def __init__(self, uid: str, origin: dict):
        self._uid, self._origin = uid, origin

    def _send(self, type_: str, content: dict) -> "_Ray":
        _post(self._uid, [{"type": type_, "content": content, "origin": self._origin}])
        return self

    def label(self, text: str) -> "_Ray":
        return self._send("label", {"label": text})

    def color(self, name: str) -> "_Ray":
        return self._send("color", {"color": name})

    def size(self, name: str) -> "_Ray":
        return self._send("size", {"size": name})

    def notify(self, text: str) -> "_Ray":
        return self._send("notify", {"value": text})

    red = lambda self: self.color("red")          # noqa: E731
    green = lambda self: self.color("green")      # noqa: E731
    blue = lambda self: self.color("blue")        # noqa: E731
    orange = lambda self: self.color("orange")    # noqa: E731
    purple = lambda self: self.color("purple")    # noqa: E731
    gray = lambda self: self.color("gray")        # noqa: E731


def ray(*values) -> _Ray:
    """Send values to the Ray desktop app. A no-op when Ray is not running. Never leave calls in committed code."""
    r = _Ray(str(uuid.uuid4()), _origin())
    if values:
        shown = [v if isinstance(v, (str, int, float, bool, type(None), list, dict)) else repr(v) for v in values]
        r._send("log", {"values": shown, "meta": [{"clipboard_data": " ".join(str(v) for v in values)[:20000]}]})
    return r


class RayQueriesMiddleware:
    """RAY_QUERIES=true: every SQL query a request runs is shown in Ray, with how long it took."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        from django.db import connections
        uid = str(uuid.uuid4())
        origin = {"function_name": "request", "file": request.path, "line_number": 0, "hostname": socket.gethostname()}

        def wrapper(execute, sql, params, many, context):
            t0 = time.perf_counter()
            try:
                return execute(sql, params, many, context)
            finally:
                _post(uid, [{"type": "executed_query", "content": {"sql": sql if not params else f"{sql}  -- {params}", "connection_name": context["connection"].alias,
                                                                  "time": round((time.perf_counter() - t0) * 1000, 2)}, "origin": origin}])
        stack = [c.execute_wrapper(wrapper) for c in connections.all()]
        for s in stack:
            s.__enter__()
        try:
            return self.get_response(request)
        finally:
            for s in reversed(stack):
                s.__exit__(None, None, None)
