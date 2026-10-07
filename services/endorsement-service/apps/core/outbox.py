"""Transactional outbox writer, same table and shape as the Spring services (outbox.outbox_events)."""
import json
import uuid

from django.db import connection, transaction


def publish(aggregate_type: str, aggregate_id, event_type: str, topic: str, partition_key,
            payload: dict, correlation_id: str | None = None) -> uuid.UUID:
    """Insert an event in the CALLER's transaction. Refuses to run outside one."""
    if not connection.in_atomic_block:
        raise RuntimeError("outbox.publish must run inside transaction.atomic()")
    event_id = uuid.uuid4()
    body = {**payload, "event_id": str(event_id), "event_type": event_type}
    with connection.cursor() as cur:
        cur.execute(
            """INSERT INTO outbox.outbox_events
               (id, aggregate_type, aggregate_id, event_type, payload, topic, partition_key, correlation_id)
               VALUES (%s, %s, %s, %s, %s::jsonb, %s, %s, %s)""",
            [str(event_id), aggregate_type, str(aggregate_id), event_type, json.dumps(body, default=str),
             topic, None if partition_key is None else str(partition_key), correlation_id or str(event_id)],
        )
    return event_id


def run_after_commit(fn):
    """Dispatch work (e.g. a Celery task) only once the surrounding transaction has committed."""
    transaction.on_commit(fn)
