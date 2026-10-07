import time

from confluent_kafka import OFFSET_BEGINNING, Consumer, TopicPartition
from django.conf import settings
from django.core.management.base import BaseCommand, CommandError

from apps.core import mongo
from apps.projections.loop import ProjectorLoop


class Command(BaseCommand):
    help = ("Drop every projection (and the event-derived RAG chunks) and replay the event topics from the beginning up to their "
            "current end. Uploaded knowledge documents are NOT touched. Stop run_projector first.")

    def add_arguments(self, parser):
        parser.add_argument("--yes", action="store_true", help="confirm that projections will be dropped")

    def handle(self, *args, yes=False, **opts):
        if not yes:
            raise CommandError("this drops all read models; pass --yes")
        db = mongo.system_db()
        for name in mongo.PROJECTION_COLLECTIONS:
            db[name].drop()
        db[mongo.RAG_CHUNKS].delete_many({"source_type": "HISTORY"})
        mongo.ensure_indexes()
        consumer = Consumer({"bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS, "group.id": f"{settings.PROJECTOR_GROUP}-rebuild-{int(time.time())}",
                             "enable.auto.commit": False, "auto.offset.reset": "earliest", "isolation.level": "read_committed"})
        try:
            md = consumer.list_topics(timeout=10)
            tps, ends = [], {}
            for topic in settings.PROJECTOR_TOPICS:
                if topic not in md.topics or md.topics[topic].error:
                    continue
                for p in md.topics[topic].partitions:
                    low, high = consumer.get_watermark_offsets(TopicPartition(topic, p), timeout=10)
                    ends[(topic, p)] = high if high > low else 0
                    tps.append(TopicPartition(topic, p, OFFSET_BEGINNING))
            consumer.assign(tps)
            loop = ProjectorLoop(consumer, db)
            loop.run(ends=ends)
            self.stdout.write(f"rebuilt from {len(tps)} partition(s): {loop.processed} events, {loop.dead} dead-lettered")
        finally:
            consumer.close()
