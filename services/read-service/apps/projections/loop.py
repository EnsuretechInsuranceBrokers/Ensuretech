"""The consume -> project -> commit loop, separated from Kafka so it can be driven by a fake consumer in tests.

Commit happens only after a message has been fully applied (at-least-once; handlers are idempotent). A transient failure (MongoDB
down) blocks on that message with backoff rather than skipping it, which preserves per-partition order. A poison message is
written to `projector_dead_letters` and skipped, so one bad event cannot wedge a partition."""
import json
import logging
import time
from datetime import UTC, datetime

from pymongo.errors import PyMongoError

from apps.core import mongo

from .handlers import PoisonEvent, handle_event

log = logging.getLogger(__name__)


class ProjectorLoop:
    def __init__(self, consumer, db, *, sleep=time.sleep, commit_every=50, max_backoff=30.0):
        self.consumer, self.db, self.sleep = consumer, db, sleep
        self.commit_every, self.max_backoff = commit_every, max_backoff
        self.processed = self.dead = 0
        self._uncommitted = 0

    def run(self, stop=lambda: False, ends: dict | None = None):
        """ends: {(topic, partition): high_watermark}; when given, return once every partition has been read up to it."""
        remaining = {k: v for k, v in (ends or {}).items() if v > 0}
        if ends is not None and not remaining:
            return
        try:
            while not stop():
                msg = self.consumer.poll(1.0)
                if msg is None:
                    self._commit()
                    continue
                if msg.error():
                    log.warning("kafka error: %s", msg.error())
                    continue
                self._apply(msg, stop)
                key = (msg.topic(), msg.partition())
                if ends is not None and key in remaining and msg.offset() + 1 >= remaining[key]:
                    del remaining[key]
                    if not remaining:
                        return
        finally:
            self._commit()

    def _apply(self, msg, stop):
        raw = msg.value()
        delay = 0.5
        while True:
            try:
                handle_event(self.db, json.loads(raw))
                break
            except (PoisonEvent, ValueError, TypeError, KeyError, AttributeError) as e:     # json errors are ValueError
                self._dead_letter(msg, raw, e)
                break
            except PyMongoError as e:
                if stop():
                    raise
                log.error("MongoDB unavailable (%s); retrying in %.1fs", type(e).__name__, delay)
                self.sleep(delay)
                delay = min(delay * 2, self.max_backoff)
        self.processed += 1
        self._uncommitted += 1
        if self._uncommitted >= self.commit_every:
            self._commit()

    def _dead_letter(self, msg, raw, err):
        self.dead += 1
        log.error("dead-lettering %s[%s]@%s: %s", msg.topic(), msg.partition(), msg.offset(), err)
        text = raw.decode("utf-8", "replace") if isinstance(raw, (bytes, bytearray)) else str(raw)
        self.db[mongo.DEAD_LETTERS].insert_one({"topic": msg.topic(), "partition": msg.partition(), "offset": msg.offset(),
                                                "raw": text[:20000], "error": str(err)[:500], "at": datetime.now(UTC)})

    def _commit(self):
        if self._uncommitted:
            self.consumer.commit(asynchronous=False)
            self._uncommitted = 0
