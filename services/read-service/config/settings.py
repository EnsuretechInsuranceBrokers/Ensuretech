from pathlib import Path

import environ

BASE_DIR = Path(__file__).resolve().parent.parent
env = environ.Env()
environ.Env.read_env(BASE_DIR / ".env")

DEBUG = env.bool("DEBUG", default=False)
SECRET_KEY = env("DJANGO_SECRET_KEY", default="dev-only-insecure-key" if DEBUG else None)
ALLOWED_HOSTS = env.list("ALLOWED_HOSTS", default=["localhost", "127.0.0.1", "testserver"])

INSTALLED_APPS = ["rest_framework", "apps.core", "apps.projections", "apps.readapi", "apps.rag"]
MIDDLEWARE = ["config.gateway.GatewaySecretMiddleware", "django.middleware.security.SecurityMiddleware", "django.middleware.common.CommonMiddleware"]
ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"
DATABASES = {}          # MongoDB only: read models are disposable and rebuilt from events; there is no relational state here
USE_TZ = True
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ("apps.core.auth.JWTTenantAuthentication",),
    "DEFAULT_PERMISSION_CLASSES": ("apps.core.permissions.GatewayPermission",),
    "DEFAULT_THROTTLE_CLASSES": ("rest_framework.throttling.ScopedRateThrottle",),
    "DEFAULT_THROTTLE_RATES": {"ask": env("ASK_THROTTLE_RATE", default="30/min")},
    "UNAUTHENTICATED_USER": None,
    "DEFAULT_RENDERER_CLASSES": ("rest_framework.renderers.JSONRenderer",),
    "DEFAULT_PARSER_CLASSES": ("rest_framework.parsers.JSONParser",),
}

JWT_PUBLIC_KEY_PATH = Path(env("JWT_PUBLIC_KEY_PATH", default=str(BASE_DIR.parent / "identity-service/keys/jwt-public.pem")))
JWT_ISSUER = env("JWT_ISSUER", default="ensuretech-identity")

# ── MongoDB (any MongoDB-wire-compatible server) ────────────────────────────────────────────────────────────
MONGO_URI = env("MONGO_URI", default="mongodb://localhost:27017")
MONGO_DB = env("MONGO_DB", default="ensuretech_read")
MONGO_TIMEOUT_MS = env.int("MONGO_TIMEOUT_MS", default=3000)

# ── Kafka projector ────────────────────────────────────────────────────────────────────────────────────────
KAFKA_BOOTSTRAP_SERVERS = env("KAFKA_BOOTSTRAP_SERVERS", default="localhost:9092")
PROJECTOR_GROUP = env("PROJECTOR_GROUP", default="read-service-projector")
PROJECTOR_TOPICS = ["endorsement.events", "policy.events", "cd-balance.updates", "tpa.events"]

# ── RAG ────────────────────────────────────────────────────────────────────────────────────────────────────
EMBEDDER = env("EMBEDDER", default="apps.rag.embedding.HashingEmbedder")
ANSWERER = env("ANSWERER", default="extractive")                   # "extractive" (no LLM) or "claude"
CLAUDE_MODEL = env("CLAUDE_MODEL", default="claude-opus-5-5")
CLAUDE_MAX_TOKENS = env.int("CLAUDE_MAX_TOKENS", default=1500)
CLAUDE_TIMEOUT_SECONDS = env.float("CLAUDE_TIMEOUT_SECONDS", default=30.0)
RAG_TOP_K = env.int("RAG_TOP_K", default=5)
RAG_MIN_SCORE = env.float("RAG_MIN_SCORE", default=0.12)
RAG_MAX_CANDIDATES = env.int("RAG_MAX_CANDIDATES", default=20000)
RAG_MAX_QUESTION_CHARS = 1000
RAG_MAX_DOC_CHARS = env.int("RAG_MAX_DOC_CHARS", default=200_000)

REPORT_MAX_ROWS = env.int("REPORT_MAX_ROWS", default=50_000)

GATEWAY_SECRET = env("GATEWAY_SECRET", default="")        # shared with the BFF (BFF_GATEWAY_SECRET), 32+ random characters; the service refuses to start without it
import sys as _sys
GATEWAY_ALLOW_UNGUARDED = env.bool("GATEWAY_ALLOW_UNGUARDED", default="pytest" in _sys.modules)   # true = answer anyone who can reach the port: local development, and the test run
GATEWAY_OPEN_CIDRS = env("GATEWAY_OPEN_CIDRS", default="")   # optional: networks allowed to reach the open doors (identity: service-token sign-in)

# Development-only debugging tools (Silk, Debug Toolbar, Ray): off unless DEV_TOOLS=true and DEBUG=true. See config/devtools.py and docs/debugging.md.
DEV_TOOLS = env.bool("DEV_TOOLS", default=False)
from config import devtools as _devtools  # noqa: E402

_devtools.configure(globals())
