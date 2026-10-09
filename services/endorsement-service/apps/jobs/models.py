import uuid

from django.db import models


class SourceType(models.TextChoices):
    GUI = "GUI"
    CSV_UPLOAD = "CSV_UPLOAD"
    LEGACY_EXCEL = "LEGACY_EXCEL"
    FAKER_TEST = "FAKER_TEST"
    DEMO = "DEMO"


class OpType(models.TextChoices):
    ADD = "ADD"
    REMOVE = "REMOVE"
    UPDATE = "UPDATE"
    COMBINE = "COMBINE"


class EndorsementJob(models.Model):
    """One unit of endorsement work for one policy. All intake paths create these."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField()
    policy_id = models.BigIntegerField()
    source_type = models.CharField(max_length=20, choices=SourceType.choices)
    op_type = models.CharField(max_length=10, choices=OpType.choices, null=True, blank=True)

    status = models.CharField(max_length=60, default="REQUEST_RAISED")
    status_code = models.IntegerField(default=100)
    phase = models.CharField(max_length=20, default="INIT")
    category = models.CharField(max_length=20, default="UPLOAD")
    is_terminal = models.BooleanField(default=False)

    correlation_id = models.CharField(max_length=80, unique=True, default=uuid.uuid4)
    idempotency_key = models.CharField(max_length=120, null=True, blank=True)
    parent_session_id = models.UUIDField(null=True, blank=True)
    schema_mapping_id = models.UUIDField(null=True, blank=True)
    source_filename = models.CharField(max_length=255, blank=True, default="")
    file_sha256 = models.CharField(max_length=64, blank=True, default="")
    effective_date = models.DateField(null=True, blank=True)

    total_rows = models.IntegerField(default=0)
    failed_rows = models.IntegerField(default=0)      # rows with at least one ERROR
    chunk_count = models.IntegerField(default=0)
    chunks_completed = models.IntegerField(default=0)
    chunks_priced = models.IntegerField(default=0)
    chunks_endorsed = models.IntegerField(default=0)
    retry_count = models.SmallIntegerField(default=0)

    total_premium = models.DecimalField(max_digits=18, decimal_places=2, null=True, blank=True)
    tax_amount = models.DecimalField(max_digits=18, decimal_places=2, null=True, blank=True)
    premium_delta = models.DecimalField(max_digits=18, decimal_places=2, null=True, blank=True)
    cd_status = models.CharField(max_length=24, null=True, blank=True)

    actor_user_id = models.CharField(max_length=64, blank=True, default="")
    actor_ip = models.GenericIPAddressField(null=True, blank=True)
    is_test_data = models.BooleanField(default=False)
    error_summary = models.JSONField(default=dict, blank=True)
    metadata = models.JSONField(default=dict, blank=True)

    started_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "endorsement_jobs"
        constraints = [
            models.UniqueConstraint(fields=["broker_id", "idempotency_key"],
                                    condition=models.Q(idempotency_key__isnull=False),
                                    name="uniq_job_idempotency_per_broker"),
        ]
        indexes = [
            models.Index(fields=["broker_id", "status"], name="idx_jobs_broker_status"),
            models.Index(fields=["employer_id", "status"], name="idx_jobs_employer_status"),
            models.Index(fields=["policy_id", "status"], name="idx_jobs_policy_status"),
            models.Index(fields=["-created_at"], name="idx_jobs_created_desc"),
        ]


class AuditTransition(models.Model):
    """Immutable record of every state change. A database trigger rejects UPDATE and DELETE."""

    job = models.ForeignKey(EndorsementJob, on_delete=models.PROTECT, related_name="audit")
    correlation_id = models.CharField(max_length=80)
    from_state = models.CharField(max_length=60)
    to_state = models.CharField(max_length=60)
    from_code = models.IntegerField()
    to_code = models.IntegerField()
    phase = models.CharField(max_length=20)
    category = models.CharField(max_length=20)
    is_terminal = models.BooleanField(default=False)
    actor = models.CharField(max_length=80)
    actor_ip = models.GenericIPAddressField(null=True, blank=True)
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField()
    policy_id = models.BigIntegerField()
    source_type = models.CharField(max_length=20)
    duration_ms = models.IntegerField(null=True, blank=True)     # time spent in the previous state
    metadata = models.JSONField(default=dict, blank=True)
    transitioned_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "audit_transitions"
        indexes = [models.Index(fields=["job", "transitioned_at"], name="idx_audit_job_time"),
                   models.Index(fields=["broker_id", "-transitioned_at"], name="idx_audit_broker_time")]
        ordering = ["id"]

    def save(self, *args, **kwargs):
        if self.pk is not None:
            raise RuntimeError("audit rows are immutable")
        super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise RuntimeError("audit rows are immutable")


class EndorsementMember(models.Model):
    """The live roster: one row per covered person per policy (final, fully indexed OLTP table)."""

    job = models.ForeignKey(EndorsementJob, on_delete=models.PROTECT, related_name="members")   # last job to change it
    policy_id = models.BigIntegerField()
    broker_id = models.BigIntegerField()
    employer_id = models.BigIntegerField()
    employee_code = models.CharField(max_length=60)
    relation_code = models.CharField(max_length=40)
    first_name = models.CharField(max_length=80)
    last_name = models.CharField(max_length=80, blank=True, default="")
    date_of_birth = models.DateField()
    gender = models.CharField(max_length=1, null=True, blank=True)
    pan = models.CharField(max_length=10, null=True, blank=True)
    mobile = models.CharField(max_length=20, null=True, blank=True)
    email = models.CharField(max_length=255, null=True, blank=True)
    grade = models.CharField(max_length=40, null=True, blank=True)
    designation = models.CharField(max_length=80, null=True, blank=True)
    annual_salary = models.DecimalField(max_digits=14, decimal_places=2, null=True, blank=True)
    sum_insured = models.DecimalField(max_digits=14, decimal_places=2)
    premium_amount = models.DecimalField(max_digits=14, decimal_places=2, null=True, blank=True)
    effective_date = models.DateField()
    end_date = models.DateField(null=True, blank=True)
    is_active = models.BooleanField(default=True)
    source_type = models.CharField(max_length=20)
    tpa_member_id = models.CharField(max_length=60, null=True, blank=True)
    tpa_member_name = models.CharField(max_length=160, null=True, blank=True)          # the name the TPA holds (it can differ from ours)
    ecard_url = models.CharField(max_length=500, null=True, blank=True)
    tpa_id_source = models.CharField(max_length=20, null=True, blank=True)             # TPA_SYNC | TPA_PORTAL | BROKER_FOR_TPA: who gave us the id
    tpa_id_assigned_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = "endorsement_members"
        constraints = [
            models.UniqueConstraint(fields=["policy_id", "employee_code", "relation_code"],
                                    condition=models.Q(is_active=True), name="uniq_active_member_per_policy"),
        ]
        indexes = [
            models.Index(fields=["policy_id", "employee_code"], name="idx_members_policy_emp"),
            models.Index(fields=["broker_id", "is_active"], name="idx_members_broker_active"),
            models.Index(fields=["pan"], name="idx_members_pan", condition=models.Q(pan__isnull=False)),
            models.Index(fields=["policy_id", "created_at"], name="idx_members_awaiting_tpa", condition=models.Q(is_active=True, tpa_member_id__isnull=True)),
        ]


class AppliedRow(models.Model):
    """Marks a staged row as applied, so a redelivered endorse task never double-applies it."""

    job_id = models.UUIDField()
    chunk_index = models.IntegerField()
    row_index = models.IntegerField()

    class Meta:
        db_table = "applied_rows"
        constraints = [models.UniqueConstraint(fields=["job_id", "chunk_index", "row_index"], name="uniq_applied_row")]


class PremiumLine(models.Model):
    """Premium result for one family (employee + dependents) in one job. Amounts are annual before pro-rata unless
    named otherwise. member_premiums is what the endorse step writes onto roster rows."""

    job_id = models.UUIDField()
    chunk_index = models.IntegerField()
    employee_code = models.CharField(max_length=60)
    effective_date = models.DateField()
    before_annual = models.DecimalField(max_digits=16, decimal_places=2)      # family priced as it is today
    after_annual = models.DecimalField(max_digits=16, decimal_places=2)       # family priced after this endorsement
    pro_rata_factor = models.DecimalField(max_digits=8, decimal_places=6)
    charged = models.DecimalField(max_digits=16, decimal_places=2)            # (after - before) * factor; negative = refund
    tax_rate = models.DecimalField(max_digits=5, decimal_places=2)
    tax = models.DecimalField(max_digits=16, decimal_places=2)
    total = models.DecimalField(max_digits=16, decimal_places=2)              # charged + tax
    # employee / employer split of `charged` (before tax); null when the policy has no contribution plan
    employee_charged = models.DecimalField(max_digits=16, decimal_places=2, null=True, blank=True)
    employer_charged = models.DecimalField(max_digits=16, decimal_places=2, null=True, blank=True)
    # [{"relation_code": "SELF", "row_index": 3 | null, "member_id": 9 | null, "premium": "1000.00"}]
    member_premiums = models.JSONField(default=list)

    class Meta:
        db_table = "premium_lines"
        indexes = [models.Index(fields=["job_id", "chunk_index"], name="idx_premium_job_chunk")]
