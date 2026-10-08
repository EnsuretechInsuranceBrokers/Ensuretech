"""Stand-in for the Celery beat schedule when there is no RabbitMQ: publishes the engine's transactional outbox to Kafka every 2 seconds.
Run from services/endorsement-service with the same environment as the engine: python scripts/relay_loop.py"""
import os, sys, time, django
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings"); sys.path.insert(0, ".")
django.setup()
from apps.core.tasks import relay_outbox
while True:
    try:
        n = relay_outbox()
        if n: print("relayed", n, flush=True)
    except Exception as e:
        print("relay error", e, flush=True)
    time.sleep(2)
