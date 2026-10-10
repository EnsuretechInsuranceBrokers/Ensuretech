import uuid

from django.db import models


class Role(models.TextChoices):
    BROKER_SPOC = "BROKER_SPOC"
    EMPLOYER_SPOC = "EMPLOYER_SPOC"
    TPA = "TPA"
    INSURER = "INSURER"


class PolicyDirectory(models.Model):
    """What we know about a policy, learned from policy events, so mails can say 'policy P-123' rather than an id."""

    policy_id = models.BigIntegerField(primary_key=True)
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField()
    policy_number = models.CharField(max_length=60, blank=True, default="")
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "policy_directory"


class Contact(models.Model):
    """Who receives mail for a role. policy_id set: that policy only. employer_id set and policy null: every policy of that
    employer. Both null: the whole broker (typical for a broker SPOC)."""

    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField(null=True, blank=True)
    policy_id = models.BigIntegerField(null=True, blank=True)
    role = models.CharField(max_length=20, choices=Role.choices)
    name = models.CharField(max_length=120, blank=True, default="")
    email = models.CharField(max_length=254)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "contacts"
        constraints = [models.UniqueConstraint(fields=["broker_id", "employer_id", "policy_id", "role", "email"], name="uniq_contact",
                                               nulls_distinct=False)]
        indexes = [models.Index(fields=["broker_id", "policy_id"]), models.Index(fields=["broker_id", "employer_id"])]


class TriggerSetting(models.Model):
    """Per-policy switch for a trigger; policy_id NULL is the broker-wide default. Policy beats broker beats the trigger's own default."""

    broker_id = models.BigIntegerField()
    policy_id = models.BigIntegerField(null=True, blank=True)
    trigger_code = models.CharField(max_length=50)
    email_enabled = models.BooleanField()
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "trigger_settings"
        constraints = [models.UniqueConstraint(fields=["broker_id", "policy_id", "trigger_code"], name="uniq_trigger_setting", nulls_distinct=False)]


class Template(models.Model):
    """A broker's own wording for a trigger. Most specific wins: policy, then employer, then broker-wide; newest active first."""

    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField(null=True, blank=True)
    policy_id = models.BigIntegerField(null=True, blank=True)
    trigger_code = models.CharField(max_length=50)
    subject = models.CharField(max_length=200)
    body_html = models.TextField()
    is_active = models.BooleanField(default=True)
    created_by = models.CharField(max_length=64, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "templates"
        indexes = [models.Index(fields=["broker_id", "trigger_code"])]


class Status(models.TextChoices):
    PENDING = "PENDING"
    SENDING = "SENDING"
    SENT = "SENT"
    DEAD = "DEAD"
    SUPPRESSED = "SUPPRESSED"


class Notification(models.Model):
    """The outbox and the delivery log in one row. Created by the event router, delivered by the sender."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    dedupe_key = models.CharField(max_length=200, unique=True)           # event_id:trigger:email, so a replayed event adds nothing
    trigger_code = models.CharField(max_length=50)
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField(null=True, blank=True)
    policy_id = models.BigIntegerField(null=True, blank=True)
    to_email = models.CharField(max_length=254)
    to_name = models.CharField(max_length=120, blank=True, default="")
    subject = models.CharField(max_length=300)
    body_html = models.TextField()
    template_id = models.BigIntegerField(null=True, blank=True)
    status = models.CharField(max_length=12, choices=Status.choices, default=Status.PENDING)
    attempts = models.IntegerField(default=0)
    next_attempt_at = models.DateTimeField(auto_now_add=True)
    lease_until = models.DateTimeField(null=True, blank=True)
    last_error = models.TextField(blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    sent_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "notifications"
        indexes = [models.Index(fields=["status", "next_attempt_at"]), models.Index(fields=["broker_id", "-created_at"]),
                   models.Index(fields=["to_email", "sent_at"])]
