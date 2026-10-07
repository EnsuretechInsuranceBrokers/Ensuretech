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

POLICY_GRPC_TARGET = env("POLICY_GRPC_TARGET", default="localhost:9090")
POLICY_GRPC_TOKEN = env("POLICY_GRPC_TOKEN", default="")
POLICY_GRPC_DEADLINE_SECONDS = env.float("POLICY_GRPC_DEADLINE_SECONDS", default=2.0)
CD_CLIENT = env("CD_CLIENT", default="apps.computation.cd.UnavailableCdClient")

MAX_UPLOAD_ROWS = env.int("MAX_UPLOAD_ROWS", default=1_000_000)
CHUNK_TARGET_ROWS = env.int("CHUNK_TARGET_ROWS", default=500)
POLICY_CLIENT = env("POLICY_CLIENT", default="apps.computation.policy_client.GrpcPolicyClient")
FILE_STORE = env("FILE_STORE", default="apps.intake.store.LocalFileStore")
UPLOAD_SPOOL_DIR = env("UPLOAD_SPOOL_DIR", default=str(Path(os.environ.get("TMPDIR", "/tmp")) / "endorsement-uploads"))
MAX_UPLOAD_BYTES = env.int("MAX_UPLOAD_BYTES", default=200 * 1024 * 1024)
# When validation passes cleanly, continue straight into premium calculation. Turn off to hold jobs for review.
PIPELINE_AUTO_CONTINUE = env.bool("PIPELINE_AUTO_CONTINUE", default=True)
