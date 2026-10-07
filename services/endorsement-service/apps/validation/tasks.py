import logging
from datetime import timedelta

from celery import shared_task
from django.conf import settings
from django.db import transaction
from django.db.models import F, Q
from django.utils import timezone

from apps.computation.policy_client import PolicyAccessError, PolicyNotFound, PolicyRejected, PolicyUnavailable, get_policy_client
from apps.core.outbox import run_after_commit
from apps.intake.models import StagedRow
from apps.jobs.machine import InvalidTransition, StaleTransition, transition
from apps.jobs.models import EndorsementJob
from apps.jobs.states import State

from .batch import failing_rows, validate_rows
from .models import ChunkState, ChunkStatus, ValidationLog

log = logging.getLogger(__name__)
STALE_AFTER = timedelta(minutes=10)      # a RUNNING chunk this old belongs to a crashed worker and may be reclaimed


def dispatch_validation(job_id) -> None:
    """Queue one task per chunk. Called after the staging transaction has committed."""
    indexes = list(ChunkState.objects.filter(job_id=job_id).order_by("chunk_index").values_list("chunk_index", flat=True))
    for idx in indexes:
        validate_chunk.apply_async(args=[str(job_id), idx])


def _claim(job_id, idx) -> bool:
    now = timezone.now()
    n = (ChunkState.objects.filter(job_id=job_id, chunk_index=idx)
         .filter(Q(status__in=[ChunkStatus.PENDING, ChunkStatus.FAILED])
                 | Q(status=ChunkStatus.RUNNING, started_at__lt=now - STALE_AFTER))
         .update(status=ChunkStatus.RUNNING, started_at=now, attempts=F("attempts") + 1))
    return n == 1


def _fail_job(job_id, idx, reason: str) -> None:
    ChunkState.objects.filter(job_id=job_id, chunk_index=idx).update(
        status=ChunkStatus.FAILED, last_error=reason[:2000], finished_at=timezone.now())
    try:
        transition(job_id, State.MANUAL_INTERVENTION_REQUIRED, metadata={"chunk": idx, "reason": reason[:500]})
    except (InvalidTransition, StaleTransition):
        log.warning("job %s could not be parked for manual intervention", job_id)


MAX_ATTEMPTS = 6                          # attempts are counted in the database, so they survive lost messages


@shared_task(name="apps.validation.tasks.validate_chunk", acks_late=True, reject_on_worker_lost=True)
def validate_chunk(job_id: str, chunk_index: int):
    if not _claim(job_id, chunk_index):
        return "skipped"                                     # already done, or another worker has it
    job = EndorsementJob.objects.get(pk=job_id)
    if job.is_terminal:
        return "job-terminal"
    try:
        transition(job_id, State.VALIDATION_IN_PROGRESS, expected_from=State.VALIDATION_QUEUED)
    except StaleTransition:
        pass                                                 # another chunk already started the job
    except InvalidTransition:
        return "job-not-validating"

    staged = list(StagedRow.objects.filter(job_id=job_id, chunk_index=chunk_index).order_by("row_index"))
    try:
        issues = validate_rows(job, staged, job.metadata.get("policy", {}), get_policy_client())
    except PolicyUnavailable as e:
        cs = ChunkState.objects.get(job_id=job_id, chunk_index=chunk_index)
        if cs.attempts >= MAX_ATTEMPTS:
            _fail_job(job_id, chunk_index, f"policy-service unavailable after {cs.attempts} attempts: {e}")
            return "failed"
        ChunkState.objects.filter(pk=cs.pk).update(status=ChunkStatus.PENDING, last_error=str(e)[:500])
        validate_chunk.apply_async(args=[job_id, chunk_index], countdown=min(2 ** cs.attempts, 60))
        return "retry-scheduled"
    except (PolicyAccessError, PolicyNotFound, PolicyRejected) as e:
        _fail_job(job_id, chunk_index, f"{type(e).__name__}: {e}")
        return "failed"

    failed = failing_rows(issues)
    with transaction.atomic():
        ValidationLog.objects.bulk_create([
            ValidationLog(job_id=job_id, chunk_index=chunk_index, row_index=i.row_index, source_line=i.source_line,
                          employee_code=i.employee_code, field_name=i.field, rule_code=i.rule, severity=i.severity,
                          message=i.message, raw_value=i.value[:500]) for i in issues], batch_size=1000)
        last = _complete_chunk(job_id, chunk_index, len(staged), len(failed))
    if last:
        finalize_validation(job_id)
    return "done"


def _complete_chunk(job_id, chunk_index, rows, failed) -> bool:
    """Marks the chunk done and bumps job counters under a row lock. True only for the call that finishes the job."""
    cs = ChunkState.objects.select_for_update().get(job_id=job_id, chunk_index=chunk_index)
    if cs.status == ChunkStatus.DONE:
        return False
    cs.status, cs.row_count, cs.failed_rows, cs.finished_at, cs.last_error = ChunkStatus.DONE, rows, failed, timezone.now(), ""
    cs.save()
    job = EndorsementJob.objects.select_for_update().get(pk=job_id)
    job.chunks_completed += 1
    job.failed_rows += failed
    job.save(update_fields=["chunks_completed", "failed_rows", "updated_at"])
    return job.chunks_completed == job.chunk_count


def finalize_validation(job_id) -> None:
    from django.db.models import Count

    job = EndorsementJob.objects.get(pk=job_id)
    summary = {r["rule_code"]: r["n"] for r in ValidationLog.objects.filter(job_id=job_id, severity="ERROR")
               .values("rule_code").annotate(n=Count("id"))}
    EndorsementJob.objects.filter(pk=job_id).update(error_summary={"errors_by_rule": summary})
    if job.failed_rows == 0:
        target = State.VALIDATION_PASSED
    elif job.failed_rows >= job.total_rows:
        target = State.VALIDATION_FAILED
    else:
        target = State.VALIDATION_PARTIAL
    transition(job_id, target, expected_from=State.VALIDATION_IN_PROGRESS,
               metadata={"failed_rows": job.failed_rows, "total_rows": job.total_rows})
    if target == State.VALIDATION_PASSED and settings.PIPELINE_AUTO_CONTINUE:
        from apps.computation.tasks import start_premium_calculation
        run_after_commit(lambda: start_premium_calculation.apply_async(args=[str(job_id)]))
