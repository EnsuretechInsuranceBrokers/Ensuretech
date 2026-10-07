import json

from pymongo.errors import AutoReconnect

from apps.core import mongo
from apps.projections.loop import ProjectorLoop

from .conftest import sample


class Msg:
    def __init__(self, topic, part, offset, value):
        self._t, self._p, self._o, self._v = topic, part, offset, value

    def topic(self): return self._t
    def partition(self): return self._p
    def offset(self): return self._o
    def value(self): return self._v if isinstance(self._v, bytes) else json.dumps(self._v).encode()
    def error(self): return None


class FakeConsumer:
    def __init__(self, msgs):
        self.msgs, self.commits, self.polls = list(msgs), 0, 0

    def poll(self, timeout):
        self.polls += 1
        return self.msgs.pop(0) if self.msgs else None

    def commit(self, asynchronous=False):
        self.commits += 1


def run_until_drained(loop, consumer):
    loop.run(stop=lambda: not consumer.msgs and consumer.polls > 0 and loop._uncommitted == 0 and loop.processed > 0 or consumer.polls > 50)


def test_applies_in_order_commits_and_dead_letters_poison_without_stopping(db):
    good = sample("policy-approved")
    msgs = [Msg("policy.events", 0, 0, good), Msg("policy.events", 0, 1, b"not json"), Msg("policy.events", 0, 2, {"event_type": "PolicyApproved", "broker_id": 1}),
            Msg("policy.events", 0, 3, {**good, "policy_id": 2})]
    c = FakeConsumer(msgs)
    loop = ProjectorLoop(c, db, sleep=lambda s: None)
    run_until_drained(loop, c)
    assert db[mongo.POLICIES].count_documents({}) == 2                                 # good events on both sides of the poison ones
    assert loop.dead == 2 and db[mongo.DEAD_LETTERS].count_documents({}) == 2
    assert c.commits >= 1


def test_a_transient_mongo_outage_blocks_on_that_message_then_succeeds(db):
    class Flaky:
        def __init__(self, real, fails): self.real, self.fails = real, fails
        def __getitem__(self, name):
            if self.fails:
                self.fails -= 1
                raise AutoReconnect("down")
            return self.real[name]
    sleeps = []
    c = FakeConsumer([Msg("policy.events", 0, 0, sample("policy-approved"))])
    loop = ProjectorLoop(c, Flaky(db, 3), sleep=sleeps.append)
    run_until_drained(loop, c)
    assert len(sleeps) == 3 and sleeps == sorted(sleeps)                               # backoff grows
    assert db[mongo.POLICIES].count_documents({}) == 1 and loop.dead == 0


def test_commits_are_batched(db):
    good = sample("policy-approved")
    c = FakeConsumer([Msg("policy.events", 0, i, {**good, "policy_id": i + 1}) for i in range(7)])
    loop = ProjectorLoop(c, db, commit_every=3)
    run_until_drained(loop, c)
    assert loop.processed == 7 and c.commits == 3                                       # after 3, after 6, and the final flush


def test_rebuild_mode_stops_when_every_partition_reaches_its_end(db):
    good = sample("policy-approved")
    c = FakeConsumer([Msg("policy.events", 0, 0, good), Msg("policy.events", 0, 1, {**good, "policy_id": 2}), Msg("endorsement.events", 0, 0, sample("endorsement-state-changed"))])
    loop = ProjectorLoop(c, db)
    loop.run(ends={("policy.events", 0): 2, ("endorsement.events", 0): 1, ("tpa.events", 0): 0})      # empty partition is not waited for
    assert loop.processed == 3 and not c.msgs


def test_rebuild_of_an_empty_log_returns_immediately(db):
    ProjectorLoop(FakeConsumer([]), db).run(ends={("policy.events", 0): 0})
