"""Publishes pending outbox rows. FOR UPDATE SKIP LOCKED makes it safe next to the Spring relays (any relay may
publish any row, exactly one at a time). At-least-once: consumers dedupe on payload.event_id."""
import logging
from typing import Protocol

from django.conf import settings
from django.db import connection, transaction

log = logging.getLogger(__name__)


class EventSender(Protocol):
    def send(self, topic: str, key: str | None, payload: str) -> None: ...


class KafkaSender:
    def __init__(self):
        from confluent_kafka import Producer

        self._p = Producer({"bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS, "enable.idempotence": True,
                            "acks": "all", "message.timeout.ms": 10000})

    def send(self, topic, key, payload):
        errors = []
        self._p.produce(topic, value=payload.encode(), key=key.encode() if key else None,
                        on_delivery=lambda err, msg: errors.append(err) if err else None)
        if self._p.flush(10) > 0:
            raise TimeoutError("Kafka delivery timed out")
        if errors:
            raise RuntimeError(str(errors[0]))


def relay_batch(sender: EventSender, batch_size: int | None = None) -> int:
    n = batch_size or settings.OUTBOX_BATCH_SIZE
    published = 0
    with transaction.atomic(), connection.cursor() as cur:
        cur.execute(
            """SELECT id, topic, partition_key, payload::text, retry_count, max_retries
               FROM outbox.outbox_events
               WHERE is_test_data = FALSE
                 AND (status = 'PENDING' OR (status = 'FAILED' AND next_retry_at <= now()))
               ORDER BY created_at LIMIT %s FOR UPDATE SKIP LOCKED""", [n])
        for ev_id, topic, key, payload, retry, max_retry in cur.fetchall():
            try:
                sender.send(topic, key, payload)
                cur.execute("UPDATE outbox.outbox_events SET status='PUBLISHED', published_at=now(), last_error=NULL "
                            "WHERE id=%s", [ev_id])
                published += 1
            except Exception as e:  # noqa: BLE001 - any transport failure is retried with backoff
                attempts = retry + 1
                dead = attempts >= max_retry
                log.warning("outbox event %s failed (attempt %s): %s", ev_id, attempts, e)
                cur.execute(
                    """UPDATE outbox.outbox_events
                       SET status = %s::public.outbox_status_enum, retry_count = %s, last_error = %s,
                           next_retry_at = now() + (%s * interval '1 second') WHERE id = %s""",
                    ["DEAD" if dead else "FAILED", attempts, str(e), min(300, 2 ** attempts), ev_id])
    return published
