import threading
from datetime import timedelta

import pytest
from django.utils import timezone

from apps.mail import sender
from apps.mail.models import Notification, Status

pytestmark = pytest.mark.django_db


def note(i=1, **kw):
    d = dict(dedupe_key=f"k{i}", trigger_code="CD_BALANCE_LOW", broker_id=1, employer_id=10, policy_id=1, to_email=f"u{i}@example.com", to_name="Una",
             subject="Deposit low", body_html="<p>Hello <b>world</b> &amp; all</p>")
    d.update(kw)
    return Notification.objects.create(**d)


def test_delivers_a_multipart_message_over_real_smtp(smtp, settings):
    settings.MAIL_FROM = "alerts@broker.test"
    n = note()
    assert sender.drain() == 1
    n.refresh_from_db()
    assert n.status == Status.SENT and n.sent_at and n.attempts == 1
    env = smtp.messages[0]
    raw = env.content.decode()
    assert env.mail_from == "alerts@broker.test" and env.rcpt_tos == ["u1@example.com"]
    assert "Subject: Deposit low" in raw and "X-Notification-Trigger: CD_BALANCE_LOW" in raw and "text/html" in raw and "text/plain" in raw
    assert "Hello world & all" in raw                                                            # plain-text alternative is derived from the HTML


def test_a_4xx_response_retries_with_backoff_then_dead_letters_at_the_cap(smtp, settings):
    smtp.rcpt_reply = "451 try again later"
    n = note()
    sender.deliver(sender.claim_next())
    n.refresh_from_db()
    assert n.status == Status.PENDING and n.attempts == 1 and n.next_attempt_at > timezone.now() + timedelta(seconds=40) and "451" in n.last_error
    assert sender.drain() == 0                                                                    # not due yet
    for _ in range(2):
        Notification.objects.filter(pk=n.pk).update(next_attempt_at=timezone.now() - timedelta(seconds=1))
        sender.drain()
    n.refresh_from_db()
    assert n.status == Status.DEAD and n.attempts == 3 and not smtp.messages


def test_a_5xx_response_is_dead_immediately(smtp):
    smtp.rcpt_reply = "550 no such user"
    n = note()
    sender.drain()
    n.refresh_from_db()
    assert n.status == Status.DEAD and n.attempts == 1


def test_an_unreachable_server_is_retried(settings):
    settings.SMTP_HOST, settings.SMTP_PORT, settings.SMTP_STARTTLS = "127.0.0.1", 1, False
    n = note()
    sender.drain()
    n.refresh_from_db()
    assert n.status == Status.PENDING and n.attempts == 1


def test_header_injection_is_refused_not_sent(smtp):
    n = note(to_email="victim@example.com\r\nBcc: evil@example.com")
    sender.drain()
    n.refresh_from_db()
    assert n.status == Status.DEAD and "invalid message" in n.last_error and not smtp.messages


def test_per_recipient_hourly_cap_suppresses_instead_of_flooding(smtp, settings):
    settings.MAIL_MAX_PER_RECIPIENT_PER_HOUR = 2
    for i in range(4):
        note(i, to_email="same@example.com")
    sender.drain()
    assert sorted(Notification.objects.values_list("status", flat=True)) == ["SENT", "SENT", "SUPPRESSED", "SUPPRESSED"]
    assert len(smtp.messages) == 2


def test_a_crashed_senders_lease_is_reclaimed_only_after_it_expires(smtp):
    n = note()
    first = sender.claim_next()
    assert first.pk == n.pk and first.status == Status.SENDING
    assert sender.claim_next() is None
    Notification.objects.filter(pk=n.pk).update(lease_until=timezone.now() - timedelta(seconds=1))
    assert sender.claim_next().pk == n.pk


@pytest.mark.django_db(transaction=True)
def test_concurrent_senders_never_claim_the_same_notification(smtp):
    for i in range(20):
        note(i)
    claimed, dupes, lock = [], [], threading.Lock()

    def worker():
        from django.db import connection
        while (n := sender.claim_next()):
            with lock:
                (dupes if n.pk in claimed else claimed).append(n.pk)
        connection.close()
    ts = [threading.Thread(target=worker) for _ in range(5)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert not dupes and len(claimed) == 20
