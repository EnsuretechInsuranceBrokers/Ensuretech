"""Delivery: claim a due notification (row lock + lease), send over SMTP, classify the outcome.
4xx / network / timeout -> retry with exponential backoff and jitter, then DEAD after MAIL_MAX_ATTEMPTS.
5xx / refused recipient / malformed headers -> DEAD at once (retrying cannot help). Per-recipient hourly cap -> SUPPRESSED."""
import logging
import random
import smtplib
from datetime import timedelta
from email.message import EmailMessage

from django.conf import settings
from django.db import transaction
from django.utils import timezone

from . import render
from .models import Notification, Status

log = logging.getLogger(__name__)


class SmtpTransport:
    def send(self, msg: EmailMessage):
        with smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT, timeout=settings.SMTP_TIMEOUT_SECONDS) as s:
            if settings.SMTP_STARTTLS:
                s.starttls()
            if settings.SMTP_USER:
                s.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
            s.send_message(msg)


def build(n: Notification) -> EmailMessage:
    msg = EmailMessage()                         # raises ValueError for CR/LF in any header: that is the header-injection guard
    msg["From"] = settings.MAIL_FROM
    msg["To"] = n.to_email if not n.to_name else f"{n.to_name.replace(chr(34), '')} <{n.to_email}>"
    msg["Subject"] = n.subject
    msg["Message-ID"] = f"<{n.id}@{settings.MAIL_FROM.split('@')[-1]}>"
    msg["X-Notification-Trigger"] = n.trigger_code
    msg.set_content(render.to_text(n.body_html))
    msg.add_alternative(f"<html><body>{n.body_html}</body></html>", subtype="html")
    return msg


def claim_next() -> Notification | None:
    now = timezone.now()
    with transaction.atomic():
        n = (Notification.objects.select_for_update(skip_locked=True)
             .filter(status=Status.PENDING, next_attempt_at__lte=now).order_by("next_attempt_at").first()
             or Notification.objects.select_for_update(skip_locked=True).filter(status=Status.SENDING, lease_until__lt=now).first())
        if n:
            n.status, n.lease_until = Status.SENDING, now + timedelta(seconds=settings.MAIL_LEASE_SECONDS)
            n.save(update_fields=["status", "lease_until"])
        return n


def _backoff(attempts: int) -> timedelta:
    secs = min(settings.MAIL_BACKOFF_MAX_SECONDS, settings.MAIL_BACKOFF_BASE_SECONDS * 2 ** max(attempts - 1, 0))
    return timedelta(seconds=secs * random.uniform(0.8, 1.2))


def deliver(n: Notification, transport=None) -> str:
    transport = transport or SmtpTransport()
    recent = Notification.objects.filter(to_email__iexact=n.to_email, status=Status.SENT,
                                         sent_at__gte=timezone.now() - timedelta(hours=1)).count()
    if recent >= settings.MAIL_MAX_PER_RECIPIENT_PER_HOUR:
        return _finish(n, Status.SUPPRESSED, f"recipient cap of {settings.MAIL_MAX_PER_RECIPIENT_PER_HOUR}/hour reached")
    try:
        transport.send(build(n))
    except ValueError as e:
        return _finish(n, Status.DEAD, f"invalid message: {e}", attempt=True)
    except smtplib.SMTPRecipientsRefused as e:
        codes = [c for c, _ in e.recipients.values()]
        if codes and all(400 <= c < 500 for c in codes):          # greylisting and the like: the server asked us to come back
            return _retry(n, f"recipient deferred: {e.recipients}")
        return _finish(n, Status.DEAD, f"recipient refused: {e.recipients}", attempt=True)
    except smtplib.SMTPResponseException as e:
        if 400 <= e.smtp_code < 500:
            return _retry(n, f"SMTP {e.smtp_code}: {e.smtp_error!r}")
        return _finish(n, Status.DEAD, f"SMTP {e.smtp_code}: {e.smtp_error!r}", attempt=True)
    except (smtplib.SMTPException, OSError) as e:
        return _retry(n, f"{type(e).__name__}: {e}")
    return _finish(n, Status.SENT, "", sent=True, attempt=True)


def _retry(n, err):
    n.attempts += 1
    if n.attempts >= settings.MAIL_MAX_ATTEMPTS:
        return _finish(n, Status.DEAD, err)
    n.status, n.last_error, n.lease_until = Status.PENDING, err[:1000], None
    n.next_attempt_at = timezone.now() + _backoff(n.attempts)
    n.save(update_fields=["status", "last_error", "lease_until", "next_attempt_at", "attempts"])
    return "retry"


def _finish(n, status, err, sent=False, attempt=False):
    if attempt:
        n.attempts += 1
    n.status, n.last_error, n.lease_until = status, err[:1000], None
    n.sent_at = timezone.now() if sent else None
    n.save(update_fields=["status", "last_error", "lease_until", "sent_at", "attempts"])
    return status.value.lower()


def drain(transport=None, limit=1000) -> int:
    n_done = 0
    while n_done < limit and (n := claim_next()):
        deliver(n, transport)
        n_done += 1
    return n_done
