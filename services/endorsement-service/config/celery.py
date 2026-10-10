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


# ── correlation id: a task carries the id of the request that queued it, so the worker's log lines can be matched to that request (config/correlation.py) ──────────────────
import logging  # noqa: E402
import time  # noqa: E402

from celery import signals  # noqa: E402

from config import correlation  # noqa: E402

_log = logging.getLogger("celery.tasks")
_started: dict = {}


@signals.before_task_publish.connect
def _carry_correlation_id(headers=None, **kwargs):
    cid = correlation.current()
    if cid and headers is not None:
        headers["x-correlation-id"] = cid


@signals.task_prerun.connect
def _task_starts(task_id=None, task=None, **kwargs):
    req = task.request
    given = req.get("x-correlation-id") or getattr(req, "x-correlation-id", None)        # a custom message header, set by _carry_correlation_id when the task was queued
    correlation.use(given or f"task-{task_id}")           # a beat task or a retry with no request behind it gets its own id
    _started[task_id] = time.perf_counter()
    _log.info("task %s started args=%s", task.name, str(task.request.args)[:200])


@signals.task_postrun.connect
def _task_ends(task_id=None, task=None, state=None, **kwargs):
    t0 = _started.pop(task_id, None)
    _log.info("task %s %s in %d ms", task.name, state, (time.perf_counter() - t0) * 1000 if t0 else 0)
    correlation.clear()
