import signal

from confluent_kafka import Consumer
from django.conf import settings
from django.core.management.base import BaseCommand

from apps.core import mongo
from apps.projections.loop import ProjectorLoop


class Command(BaseCommand):
    help = "Consume the platform's event topics and keep the MongoDB read models current."

    def handle(self, *args, **opts):
        mongo.ensure_indexes()
        consumer = Consumer({"bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS, "group.id": settings.PROJECTOR_GROUP,
                             "enable.auto.commit": False, "auto.offset.reset": "earliest", "isolation.level": "read_committed"})
        consumer.subscribe(settings.PROJECTOR_TOPICS)
        stopping = []
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: stopping.append(1))
        loop = ProjectorLoop(consumer, mongo.system_db())
        try:
            loop.run(stop=lambda: bool(stopping))
        finally:
            consumer.close()
            self.stdout.write(f"projector stopped: {loop.processed} processed, {loop.dead} dead-lettered")
