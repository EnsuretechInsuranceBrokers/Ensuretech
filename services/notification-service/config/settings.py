from pathlib import Path

import environ

BASE_DIR = Path(__file__).resolve().parent.parent
env = environ.Env()
environ.Env.read_env(BASE_DIR / ".env")

DEBUG = env.bool("DEBUG", default=False)
SECRET_KEY = env("DJANGO_SECRET_KEY", default="dev-only-insecure-key" if DEBUG else None)
ALLOWED_HOSTS = env.list("ALLOWED_HOSTS", default=["localhost", "127.0.0.1", "testserver"])

INSTALLED_APPS = ["rest_framework", "apps.core", "apps.mail"]
MIDDLEWARE = ["config.gateway.GatewaySecretMiddleware", "django.middleware.security.SecurityMiddleware", "django.middleware.common.CommonMiddleware"]
ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"

DATABASES = {"default": env.db("DATABASE_URL", default="postgres://ensuretech:ensuretech@localhost:5432/ensuretech")}
DATABASES["default"]["OPTIONS"] = {"options": "-c search_path=notification,public"}
DATABASES["default"]["CONN_MAX_AGE"] = 60
DATABASES["default"]["TEST"] = {"CHARSET": "UTF8", "TEMPLATE": "template0"}
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
USE_TZ = True
TIME_ZONE = "UTC"
TEMPLATES = []          # mail bodies are rendered by apps.mail.render (placeholders only), never by a template engine

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ("apps.core.auth.JWTTenantAuthentication",),
    "DEFAULT_PERMISSION_CLASSES": ("apps.core.permissions.GatewayPermission",),
    "UNAUTHENTICATED_USER": None,
    "DEFAULT_RENDERER_CLASSES": ("rest_framework.renderers.JSONRenderer",),
    "DEFAULT_PARSER_CLASSES": ("rest_framework.parsers.JSONParser",),
}

JWT_PUBLIC_KEY_PATH = Path(env("JWT_PUBLIC_KEY_PATH", default=str(BASE_DIR.parent / "identity-service/keys/jwt-public.pem")))
JWT_ISSUER = env("JWT_ISSUER", default="ensuretech-identity")

KAFKA_BOOTSTRAP_SERVERS = env("KAFKA_BOOTSTRAP_SERVERS", default="localhost:9092")
CONSUMER_GROUP = env("CONSUMER_GROUP", default="notification-service")
CONSUMER_TOPICS = ["endorsement.events", "policy.events", "cd-balance.updates", "tpa.events"]

# ── SMTP ───────────────────────────────────────────────────────────────────────────────────────────────────
SMTP_HOST = env("SMTP_HOST", default="localhost")
SMTP_PORT = env.int("SMTP_PORT", default=25)
SMTP_USER = env("SMTP_USER", default="")
SMTP_PASSWORD = env("SMTP_PASSWORD", default="")
SMTP_STARTTLS = env.bool("SMTP_STARTTLS", default=True)
SMTP_TIMEOUT_SECONDS = env.float("SMTP_TIMEOUT_SECONDS", default=15.0)
MAIL_FROM = env("MAIL_FROM", default="no-reply@example.com")
MAIL_BRAND = env("MAIL_BRAND", default="Ensuretech")

# ── delivery policy ────────────────────────────────────────────────────────────────────────────────────────
MAIL_MAX_ATTEMPTS = env.int("MAIL_MAX_ATTEMPTS", default=6)
MAIL_BACKOFF_BASE_SECONDS = env.int("MAIL_BACKOFF_BASE_SECONDS", default=60)
MAIL_BACKOFF_MAX_SECONDS = env.int("MAIL_BACKOFF_MAX_SECONDS", default=3600)
MAIL_LEASE_SECONDS = env.int("MAIL_LEASE_SECONDS", default=300)
MAIL_MAX_PER_RECIPIENT_PER_HOUR = env.int("MAIL_MAX_PER_RECIPIENT_PER_HOUR", default=60)

GATEWAY_SECRET = env("GATEWAY_SECRET", default="")        # shared with the BFF (BFF_GATEWAY_SECRET), 32+ random characters; the service refuses to start without it
import sys as _sys
GATEWAY_ALLOW_UNGUARDED = env.bool("GATEWAY_ALLOW_UNGUARDED", default="pytest" in _sys.modules)   # true = answer anyone who can reach the port: local development, and the test run
GATEWAY_OPEN_CIDRS = env("GATEWAY_OPEN_CIDRS", default="")   # optional: networks allowed to reach the open doors (identity: service-token sign-in)

# Development-only debugging tools (Silk, Debug Toolbar, Ray): off unless DEV_TOOLS=true and DEBUG=true. See config/devtools.py and docs/debugging.md.
DEV_TOOLS = env.bool("DEV_TOOLS", default=False)
from config import devtools as _devtools  # noqa: E402

_devtools.configure(globals())
