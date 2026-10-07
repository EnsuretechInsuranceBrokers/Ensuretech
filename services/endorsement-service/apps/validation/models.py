from django.db import models


class ChunkStatus(models.TextChoices):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    DONE = "DONE"
    FAILED = "FAILED"


class ChunkState(models.Model):
    """Coordination row for one validation chunk; the compare-and-set on `status` is what makes redelivery safe."""

    job_id = models.UUIDField()
    chunk_index = models.IntegerField()
    status = models.CharField(max_length=10, choices=ChunkStatus.choices, default=ChunkStatus.PENDING)
    row_count = models.IntegerField(default=0)
    failed_rows = models.IntegerField(default=0)
    attempts = models.SmallIntegerField(default=0)
    started_at = models.DateTimeField(null=True, blank=True)
    finished_at = models.DateTimeField(null=True, blank=True)
    last_error = models.TextField(blank=True, default="")

    class Meta:
        db_table = "chunk_states"
        constraints = [models.UniqueConstraint(fields=["job_id", "chunk_index"], name="uniq_chunk_per_job")]


class ValidationLog(models.Model):
    job_id = models.UUIDField()
    chunk_index = models.IntegerField()
    row_index = models.IntegerField()
    source_line = models.IntegerField()
    employee_code = models.CharField(max_length=60, blank=True, default="")
    field_name = models.CharField(max_length=60, blank=True, default="")
    rule_code = models.CharField(max_length=60)
    severity = models.CharField(max_length=8, default="ERROR")        # ERROR blocks the row; WARN does not
    message = models.TextField()
    raw_value = models.TextField(blank=True, default="")
    logged_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "validation_logs"
        indexes = [models.Index(fields=["job_id", "severity", "source_line"], name="idx_vlog_job_sev")]
