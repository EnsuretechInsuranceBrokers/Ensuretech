import logging

from celery import shared_task
from django.conf import settings
from django.db import transaction
from django.db.models import Count

from apps.computation.policy_client import (PolicyAccessError, PolicyNotFound, PolicyRejected, PolicyUnavailable,
                                            get_policy_client)
from apps.core.outbox import run_after_commit
from apps.intake.models import StagedRow
from apps.jobs import chunks
from apps.jobs.machine import InvalidTransition, StaleTransition, transition
from apps.jobs.models import EndorsementJob
from apps.jobs.states import State

from .batch import failing_rows, validate_rows
from .models import ValidationLog

log = logging.getLogger(__name__)
STAGE = "VALIDATE"
MAX_ATTEMPTS = 6                          # attempts are counted in the database, so they survive lost messages


def dispatch_validation(job_id, queue: str | None = None) -> None:
    """Queue one task per chunk. Called after the staging transaction has committed. Interactive (GUI) jobs pass
    queue="validation.high" so a person waiting on a screen never sits behind a 1M-row file."""
    for idx in chunks.stage_indexes(job_id, STAGE):
        validate_chunk.apply_async(args=[str(job_id), idx], **({"queue": queue} if queue else {}))


def _fail_job(job_id, idx, reason: str) -> None:
    chunks.mark_failed(job_id, idx, STAGE, reason)
    try:
        transition(job_id, State.MANUAL_INTERVENTION_REQUIRED, metadata={"chunk": idx, "reason": reason[:500]})
    except (InvalidTransition, StaleTransition):
        log.warning("job %s could not be parked for manual intervention", job_id)


@shared_task(name="apps.validation.tasks.validate_chunk", acks_late=True, reject_on_worker_lost=True)
def validate_chunk(job_id: str, chunk_index: int):
    if not chunks.claim(job_id, chunk_index, STAGE):
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
        n = chunks.attempts(job_id, chunk_index, STAGE)
        if n >= MAX_ATTEMPTS:
            _fail_job(job_id, chunk_index, f"policy-service unavailable after {n} attempts: {e}")
            return "failed"
        chunks.release(job_id, chunk_index, STAGE, str(e))
        validate_chunk.apply_async(args=[job_id, chunk_index], countdown=min(2 ** n, 60))
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
        last = chunks.complete(job_id, chunk_index, STAGE, counter="chunks_completed", rows=len(staged),
                               failed=len(failed), job_increments={"failed_rows": len(failed)})
    if last:
        finalize_validation(job_id)
    return "done"


def finalize_validation(job_id) -> None:
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
