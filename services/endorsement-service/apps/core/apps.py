from django.apps import AppConfig
from django.db.models.signals import pre_migrate


def _ensure_schema(sender, using=None, **kwargs):
    """Create the service schema before the first migration so django_migrations lands inside it."""
    from django.db import connections

    with connections[using or "default"].cursor() as cur:
        cur.execute("CREATE SCHEMA IF NOT EXISTS endorsement")


class CoreConfig(AppConfig):
    name = "apps.core"
    label = "core"

    def ready(self):
        pre_migrate.connect(_ensure_schema, dispatch_uid="endorsement_ensure_schema")
