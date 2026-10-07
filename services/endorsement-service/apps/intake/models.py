import uuid

from django.db import models


class SchemaMapping(models.Model):
    """A confirmed CSV header -> canonical field mapping, reusable for recurring uploads from the same source."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField(null=True, blank=True)
    source_name = models.CharField(max_length=120)          # e.g. "DarwinBox export", "Care Health TPA"
    source_key = models.CharField(max_length=40, blank=True, default="")   # selects TPA-specific normalisation
    column_map = models.JSONField()                          # {"Emp Code": "employee_code", ...}
    confidence = models.JSONField(default=dict, blank=True)
    options = models.JSONField(default=dict, blank=True)     # e.g. {"dob_format": "%m/%d/%Y"}
    version = models.SmallIntegerField(default=1)
    is_confirmed = models.BooleanField(default=False)
    last_used_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "schema_mappings"
        indexes = [models.Index(fields=["broker_id", "employer_id"], name="idx_mapping_tenant")]


class StagedRow(models.Model):
    """Append-only ingestion table. Deliberately no FK to the job (cheap bulk inserts) and a single index that
    the chunk workers need to read a chunk back."""

    job_id = models.UUIDField(db_index=False)
    chunk_index = models.IntegerField()
    row_index = models.IntegerField()                       # position within the job, 0-based
    source_line = models.IntegerField()                     # physical line in the uploaded file (for error reports)
    op_type = models.CharField(max_length=10)
    raw_payload = models.JSONField()
    normalised_payload = models.JSONField()
    row_hash = models.CharField(max_length=64)
    ingested_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "staged_rows"
        indexes = [models.Index(fields=["job_id", "chunk_index", "row_index"], name="idx_staged_job_chunk")]


class GuiSessionState(models.TextChoices):
    OPEN = "OPEN"
    SUBMITTED = "SUBMITTED"
    ABANDONED = "ABANDONED"


class GuiSession(models.Model):
    """An HR user editing endorsement rows in the grid. Rows are staged here, validated live, and promoted into
    typed jobs only on submit, so nothing half-edited ever reaches the pipeline."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField()
    policy_id = models.BigIntegerField()
    actor_user_id = models.CharField(max_length=64, blank=True, default="")
    state = models.CharField(max_length=12, choices=GuiSessionState.choices, default=GuiSessionState.OPEN)
    effective_date = models.DateField(null=True, blank=True)
    policy_facts = models.JSONField(default=dict)
    submitted_jobs = models.JSONField(default=list, blank=True)       # [{"job_id", "op_type", "rows"}] once submitted
    expires_at = models.DateTimeField()
    submitted_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "gui_sessions"
        indexes = [models.Index(fields=["broker_id", "state"], name="idx_gui_tenant_state"),
                   models.Index(fields=["expires_at"], name="idx_gui_expiry", condition=models.Q(state="OPEN"))]


class GuiRow(models.Model):
    session = models.ForeignKey(GuiSession, on_delete=models.CASCADE, related_name="rows")
    row_key = models.CharField(max_length=64)                         # the grid's own id for the row
    op_type = models.CharField(max_length=10)
    raw = models.JSONField()
    normalised = models.JSONField()
    issues = models.JSONField(default=list)                           # [{field, rule, severity, message}]
    employee_code = models.CharField(max_length=60, blank=True, default="")
    relation_code = models.CharField(max_length=40, blank=True, default="")
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "gui_rows"
        constraints = [models.UniqueConstraint(fields=["session", "row_key"], name="uniq_gui_row_key")]
        indexes = [models.Index(fields=["session", "op_type", "employee_code", "relation_code"], name="idx_gui_row_ident")]
