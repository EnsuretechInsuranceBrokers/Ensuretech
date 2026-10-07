import pytest
from django.db import connection

from apps.core import outbox, relay


class Sender:
    def __init__(self):
        self.sent, self.fail = [], False

    def send(self, topic, key, payload):
        if self.fail:
            raise RuntimeError("kafka down")
        self.sent.append((topic, key, payload))


def _publish(n=1):
    for i in range(n):
        outbox.publish("T", i, "Evt", "endorsement.events", 7, {"i": i})


def _count(where):
    with connection.cursor() as cur:
        cur.execute(f"SELECT count(*) FROM outbox.outbox_events WHERE {where}")
        return cur.fetchone()[0]


@pytest.mark.django_db(transaction=True)
def test_relay_publishes_once_in_order_with_event_id():
    import json
    from django.db import transaction
    with transaction.atomic():
        _publish(3)
    s = Sender()
    assert relay.relay_batch(s) == 3
    assert relay.relay_batch(s) == 0
    assert [json.loads(p)["i"] for _, _, p in s.sent] == [0, 1, 2]
    assert all(k == "7" for _, k, _ in s.sent)
    assert all(json.loads(p)["event_id"] for _, _, p in s.sent)
    with connection.cursor() as cur:
        cur.execute("DELETE FROM outbox.outbox_events")


@pytest.mark.django_db(transaction=True)
def test_failures_back_off_then_dead_letter_and_recover():
    from django.db import transaction
    with transaction.atomic():
        _publish(1)
    s = Sender()
    s.fail = True
    for _ in range(5):
        relay.relay_batch(s)
        with connection.cursor() as cur:
            cur.execute("UPDATE outbox.outbox_events SET next_retry_at = now() - interval '1 second' WHERE status='FAILED'")
    assert _count("status='DEAD' AND retry_count=5 AND last_error LIKE '%kafka down%'") == 1
    s.fail = False
    assert relay.relay_batch(s) == 0                      # dead rows are not retried automatically
    with connection.cursor() as cur:
        cur.execute("DELETE FROM outbox.outbox_events")


def test_publish_outside_a_transaction_is_refused(transactional_db):
    with pytest.raises(RuntimeError, match="atomic"):
        outbox.publish("T", 1, "Evt", "t", 1, {})
