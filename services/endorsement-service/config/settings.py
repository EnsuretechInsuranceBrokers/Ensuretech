import os
from pathlib import Path

import environ

BASE_DIR = Path(__file__).resolve().parent.parent
env = environ.Env()
environ.Env.read_env(BASE_DIR / ".env")

DEBUG = env.bool("DEBUG", default=False)
SECRET_KEY = env("DJANGO_SECRET_KEY", default="dev-only-insecure-key" if DEBUG else None)
ALLOWED_HOSTS = env.list("ALLOWED_HOSTS", default=["localhost", "127.0.0.1", "testserver"])

INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "django.contrib.auth",
    "rest_framework",
    "apps.core",
    "apps.jobs",
    "apps.intake",
    "apps.validation",
    "apps.computation",
]

MIDDLEWARE = [
    "config.gateway.GatewaySecretMiddleware",
    "django.middleware.security.SecurityMiddleware",
    "django.middleware.common.CommonMiddleware",
]

ROOT_URLCONF = "config.urls"
WSGI_APPLICATION = "config.wsgi.application"
ASGI_APPLICATION = "config.asgi.application"

# One PostgreSQL database for the platform; this service owns the `endorsement` schema
# (created by a pre_migrate hook in apps.core so django_migrations also lives there).
DATABASES = {"default": env.db("DATABASE_URL", default="postgres://ensuretech:ensuretech@localhost:5432/ensuretech")}
DATABASES["default"]["OPTIONS"] = {"options": "-c search_path=endorsement,public"}
DATABASES["default"]["CONN_MAX_AGE"] = 60
# Indian HR files contain non-ASCII (₹, names): the database must be UTF8, and tests must not depend on the cluster default.
DATABASES["default"]["TEST"] = {"CHARSET": "UTF8", "TEMPLATE": "template0"}
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
USE_TZ = True
TIME_ZONE = "UTC"

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ("apps.core.auth.JWTTenantAuthentication",),
    "DEFAULT_PERMISSION_CLASSES": ("apps.core.permissions.GatewayPermission",),
    "DEFAULT_RENDERER_CLASSES": ("rest_framework.renderers.JSONRenderer",),
    "DEFAULT_PARSER_CLASSES": (
        "rest_framework.parsers.JSONParser",
        "rest_framework.parsers.MultiPartParser",
    ),
    "EXCEPTION_HANDLER": "apps.core.exceptions.handler",
    "UNAUTHENTICATED_USER": None,
}

# ── JWT (resource server only: verifies identity-service tokens with the public key) ──────────────────────
JWT_PUBLIC_KEY_PATH = env("JWT_PUBLIC_KEY_PATH", default=str(BASE_DIR / "../identity-service/keys/jwt-public.pem"))
JWT_ISSUER = env("JWT_ISSUER", default="ensuretech-identity")

# ── Celery ─────────────────────────────────────────────────────────────────────────────────────────────────
CELERY_BROKER_URL = env("CELERY_BROKER_URL", default="amqp://guest:guest@localhost:5672//")
CELERY_RESULT_BACKEND = None          # state lives in PostgreSQL, not in a result backend
CELERY_TASK_ALWAYS_EAGER = env.bool("CELERY_TASK_ALWAYS_EAGER", default=False)
CELERY_TASK_EAGER_PROPAGATES = True
CELERY_TASK_ACKS_LATE = True
CELERY_TASK_REJECT_ON_WORKER_LOST = True
CELERY_WORKER_PREFETCH_MULTIPLIER = 1
CELERY_WORKER_MAX_TASKS_PER_CHILD = 50
CELERY_BROKER_HEARTBEAT = 10
CELERY_TASK_SERIALIZER = "json"
CELERY_ACCEPT_CONTENT = ["json"]
CELERY_TIMEZONE = "UTC"
CELERY_BEAT_SCHEDULE = {
    "relay-outbox": {"task": "apps.core.tasks.relay_outbox", "schedule": 5.0},
}

# ── Messaging / integrations ─────────────────────────────────────────────────────────────────────────────
KAFKA_BOOTSTRAP_SERVERS = env("KAFKA_BOOTSTRAP_SERVERS", default="localhost:9092")
EVENTS_TOPIC = "endorsement.events"
OUTBOX_BATCH_SIZE = 100
OUTBOX_MAX_RETRIES = 5

# Client-credentials (preferred). When unset, the static *_GRPC_TOKEN values below are used (dev/tests).
IDENTITY_TOKEN_URL = env("IDENTITY_TOKEN_URL", default="")            # e.g. http://identity:8000/api/v1/auth/service-token/
SERVICE_CLIENT_ID = env("SERVICE_CLIENT_ID", default="")
SERVICE_CLIENT_SECRET = env("SERVICE_CLIENT_SECRET", default="")
IDENTITY_TOKEN_TIMEOUT_SECONDS = env.float("IDENTITY_TOKEN_TIMEOUT_SECONDS", default=3.0)

POLICY_GRPC_TARGET = env("POLICY_GRPC_TARGET", default="localhost:9090")
POLICY_GRPC_TOKEN = env("POLICY_GRPC_TOKEN", default="")
POLICY_GRPC_DEADLINE_SECONDS = env.float("POLICY_GRPC_DEADLINE_SECONDS", default=2.0)
CD_CLIENT = env("CD_CLIENT", default="apps.computation.cd.GrpcCdClient")
BILLING_GRPC_TARGET = env("BILLING_GRPC_TARGET", default="localhost:9091")
BILLING_GRPC_TOKEN = env("BILLING_GRPC_TOKEN", default="")           # SYSTEM service-account JWT
CD_GRPC_DEADLINE_SECONDS = env.float("CD_GRPC_DEADLINE_SECONDS", default=0.5)

MAX_UPLOAD_ROWS = env.int("MAX_UPLOAD_ROWS", default=1_000_000)
CHUNK_TARGET_ROWS = env.int("CHUNK_TARGET_ROWS", default=500)
POLICY_CLIENT = env("POLICY_CLIENT", default="apps.computation.policy_client.GrpcPolicyClient")
FILE_STORE = env("FILE_STORE", default="apps.intake.store.LocalFileStore")
UPLOAD_SPOOL_DIR = env("UPLOAD_SPOOL_DIR", default=str(Path(os.environ.get("TMPDIR", "/tmp")) / "endorsement-uploads"))
MAX_UPLOAD_BYTES = env.int("MAX_UPLOAD_BYTES", default=200 * 1024 * 1024)
# When validation passes CLEANLY, continue straight into premium calculation. Turn off to hold every job for a human
# to confirm via /proceed/. (Once a job is confirmed or auto-started, the remaining steps always run.)
PIPELINE_AUTO_CONTINUE = env.bool("PIPELINE_AUTO_CONTINUE", default=True)

GUI_SESSION_TTL_HOURS = env.int("GUI_SESSION_TTL_HOURS", default=4)
GUI_VALIDATE_RATE_PER_MINUTE = env.int("GUI_VALIDATE_RATE_PER_MINUTE", default=100)
SSE_POLL_SECONDS = env.float("SSE_POLL_SECONDS", default=0.5)
SSE_MAX_SECONDS = env.int("SSE_MAX_SECONDS", default=600)
SSE_HEARTBEAT_SECONDS = env.int("SSE_HEARTBEAT_SECONDS", default=15)
# Shared cache for rate limits (LocMem would be per-process and let a user multiply their budget by worker count).
CACHES = {"default": ({"BACKEND": "django.core.cache.backends.redis.RedisCache", "LOCATION": env("REDIS_URL")}
                      if env("REDIS_URL", default="") else {"BACKEND": "django.core.cache.backends.locmem.LocMemCache"})}
CELERY_BEAT_SCHEDULE["expire-gui-sessions"] = {"task": "apps.intake.tasks.expire_gui_sessions", "schedule": 300.0}

# ── pro-rata (legacy parity; both are per-portal switches there) ─────────────────────────────────────────────────
REMOVAL_INCLUDES_LEAVING_DAY = env.bool("REMOVAL_INCLUDES_LEAVING_DAY", default=True)        # False reproduces the TATA AIG same-day-removal variant
REMOVAL_REFUND_WHEN_PRORATA_DISABLED = env("REMOVAL_REFUND_WHEN_PRORATA_DISABLED", default="DAILY")   # DAILY (legacy) | FULL | NONE

GATEWAY_SECRET = env("GATEWAY_SECRET", default="")        # shared with the BFF; blank = the check is off (local development)
