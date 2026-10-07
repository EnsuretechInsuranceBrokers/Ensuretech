import json

import pytest
from django.db import OperationalError

from apps.mail.management.commands.run_consumer import consume
from apps.mail.models import Contact, Notification

from .conftest import sample

pytestmark = pytest.mark.django_db


class Msg:
    def __init__(self, v): self.v = v
    def value(self): return self.v if isinstance(self.v, bytes) else json.dumps(self.v).encode()
    def error(self): return None
    def topic(self): return "t"
    def partition(self): return 0
    def offset(self): return 0


class C:
    def __init__(self, msgs): self.msgs, self.commits = list(msgs), 0
    def poll(self, t): return self.msgs.pop(0) if self.msgs else None
    def commit(self, message=None, asynchronous=False): self.commits += 1


def test_consumer_routes_skips_poison_and_commits_every_message(monkeypatch):
    Contact.objects.create(broker_id=1, role="BROKER_SPOC", email="b@b.com")
    good = {**sample("endorsement-state-changed"), "to_state": "CD_INSUFFICIENT"}
    c = C([Msg(good), Msg(b"not json"), Msg({"event_type": "EndorsementStateChanged", "to_state": "ENDORSED"})])
    consume(c, stop=lambda: not c.msgs, housekeeping=lambda: None)
    assert Notification.objects.count() == 1 and c.commits == 3


def test_a_database_outage_blocks_on_the_message_then_succeeds(monkeypatch):
    Contact.objects.create(broker_id=1, role="BROKER_SPOC", email="b@b.com")
    from apps.mail import routing
    real, calls = routing.process_event, {"n": 0}

    def flaky(e):
        calls["n"] += 1
        if calls["n"] <= 2:
            raise OperationalError("db down")
        return real(e)
    monkeypatch.setattr("apps.mail.management.commands.run_consumer.process_event", flaky)
    sleeps = []
    c = C([Msg({**sample("endorsement-state-changed"), "to_state": "CD_INSUFFICIENT"})])
    consume(c, stop=lambda: c.commits >= 1, sleep=sleeps.append, housekeeping=lambda: None)
    assert len(sleeps) == 2 and Notification.objects.count() == 1 and c.commits == 1
