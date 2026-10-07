from celery import shared_task

from . import relay


@shared_task(name="apps.core.tasks.relay_outbox")
def relay_outbox() -> int:
    return relay.relay_batch(relay.KafkaSender())
