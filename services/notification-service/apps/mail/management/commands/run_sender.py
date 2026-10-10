import signal
import time

from django.core.management.base import BaseCommand

from apps.mail import sender


class Command(BaseCommand):
    help = "Deliver queued notifications over SMTP."

    def add_arguments(self, p):
        p.add_argument("--idle-seconds", type=float, default=2.0)

    def handle(self, *a, idle_seconds=2.0, **o):
        stopping = []
        for s in (signal.SIGINT, signal.SIGTERM):
            signal.signal(s, lambda *_: stopping.append(1))
        while not stopping:
            if not sender.drain(limit=100):
                time.sleep(idle_seconds)
