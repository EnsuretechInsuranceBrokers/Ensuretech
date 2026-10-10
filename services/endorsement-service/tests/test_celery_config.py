"""The Celery topology the design promises, and what Flower needs from it."""
import pytest
from django.conf import settings

from config.celery import app


@pytest.mark.parametrize("task,queue", [
    ("apps.intake.tasks.ingest_csv", "csv.ingestion"),
    ("apps.validation.tasks.validate_chunk", "validation.standard"),
    ("apps.computation.tasks.start_premium_calculation", "computation"),
    ("apps.computation.tasks.price_chunk", "computation"),
    ("apps.computation.tasks.check_cd_balance", "computation"),
    ("apps.computation.tasks.apply_chunk", "computation"),
    ("apps.core.tasks.relay_outbox", "maintenance"),
    ("apps.intake.tasks.expire_gui_sessions", "maintenance"),
])
def test_each_task_goes_to_its_own_bulkhead_queue(task, queue):
    app.loader.import_default_modules()
    assert task in app.tasks, f"{task} is not a registered task"
    assert app.amqp.router.route({}, task)["queue"].name == queue


def test_every_queue_has_a_dead_letter_queue():
    names = {q.name for q in app.conf.task_queues}
    for q in ("csv.ingestion", "validation.high", "validation.standard", "gui.batch", "computation", "maintenance"):
        assert q in names and f"dlq.{q}" in names


def test_workers_emit_the_events_flower_is_built_from():
    assert settings.CELERY_WORKER_SEND_TASK_EVENTS and settings.CELERY_TASK_SEND_SENT_EVENT and settings.CELERY_TASK_TRACK_STARTED
    assert settings.CELERY_TASK_ACKS_LATE and settings.CELERY_TASK_REJECT_ON_WORKER_LOST
