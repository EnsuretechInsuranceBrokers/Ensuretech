from django.apps import AppConfig
from django.db.models.signals import pre_migrate


def _ensure_schema(sender, using=None, **kwargs):
    from django.db import connections

    if connections[using or "default"].vendor != "postgresql":
        return                                  # e.g. the throwaway SQLite file of the development tools (config/devtools.py)
    with connections[using or "default"].cursor() as cur:
        cur.execute("CREATE SCHEMA IF NOT EXISTS notification")


class CoreConfig(AppConfig):
    name = "apps.core"
    label = "core"

    def ready(self):
        pre_migrate.connect(_ensure_schema, dispatch_uid="notification_ensure_schema")
