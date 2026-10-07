import json
import logging
import signal
import time

from confluent_kafka import Consumer
from django.conf import settings
from django.core.management.base import BaseCommand
from django.db import DatabaseError, close_old_connections

from apps.mail.routing import PoisonEvent, process_event

log = logging.getLogger(__name__)


def consume(consumer, stop=lambda: False, sleep=time.sleep, housekeeping=close_old_connections):
    """poll -> route -> commit. Poison messages are logged and skipped; database trouble blocks on the message and retries."""
    while not stop():
        msg = consumer.poll(1.0)
        if msg is None or msg.error():
            continue
        delay = 0.5
        while True:
            try:
                housekeeping()                      # drops connections the server or CONN_MAX_AGE has retired
                process_event(json.loads(msg.value()))
                break
            except (PoisonEvent, ValueError, TypeError, KeyError) as e:
                log.error("skipping unprocessable event %s[%s]@%s: %s", msg.topic(), msg.partition(), msg.offset(), e)
                break
            except DatabaseError as e:
                if stop():
                    return
                log.error("database unavailable (%s: %s); retrying in %.1fs", type(e).__name__, e, delay)
                sleep(delay)
                delay = min(delay * 2, 30)
        consumer.commit(message=msg, asynchronous=False)


class Command(BaseCommand):
    help = "Consume platform events and queue the notifications they trigger."

    def handle(self, *a, **o):
        c = Consumer({"bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS, "group.id": settings.CONSUMER_GROUP,
                      "enable.auto.commit": False, "auto.offset.reset": "earliest", "isolation.level": "read_committed"})
        c.subscribe(settings.CONSUMER_TOPICS)
        stopping = []
        for s in (signal.SIGINT, signal.SIGTERM):
            signal.signal(s, lambda *_: stopping.append(1))
        try:
            consume(c, stop=lambda: bool(stopping))
        finally:
            c.close()
