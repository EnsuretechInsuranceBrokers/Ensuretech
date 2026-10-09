import os

from celery import Celery
from kombu import Exchange, Queue

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

app = Celery("endorsement")
app.config_from_object("django.conf:settings", namespace="CELERY")

# Bulkhead queues (design: no queue shares workers with another). Each dead-letters to its own DLQ.
_DLX = Exchange("dlx", type="direct")


def _q(name: str, priority: int = 5) -> Queue:
    return Queue(
        name,
        Exchange(name),
        routing_key=name,
        queue_arguments={
            "x-max-priority": 10,
            "x-dead-letter-exchange": "dlx",
            "x-dead-letter-routing-key": f"dlq.{name}",
        },
    )


app.conf.task_queues = [_q(n) for n in (
    "csv.ingestion", "validation.high", "validation.standard", "gui.batch", "computation", "maintenance",
)] + [Queue(f"dlq.{n}", _DLX, routing_key=f"dlq.{n}") for n in (
    "csv.ingestion", "validation.high", "validation.standard", "gui.batch", "computation", "maintenance",
)]
app.conf.task_default_queue = "maintenance"
app.conf.task_routes = {
    "apps.intake.tasks.ingest_csv": {"queue": "csv.ingestion"},        # the bulkhead the queue list above promises: a big file must not sit in the maintenance queue
    "apps.validation.tasks.validate_chunk": {"queue": "validation.standard"},
    "apps.validation.tasks.finalize_validation": {"queue": "validation.standard"},
    "apps.computation.tasks.*": {"queue": "computation"},
    "apps.core.tasks.*": {"queue": "maintenance"},
}
app.autodiscover_tasks()
