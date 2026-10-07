import logging
from decimal import Decimal

from celery import shared_task
from django.conf import settings
from django.db import transaction
from django.db.models import Sum

from apps.computation.cd import CdUnavailable, get_cd_client
from apps.computation.policy_client import (PolicyAccessError, PolicyNotFound, PolicyRejected, PolicyUnavailable,
                                            get_policy_client)
from apps.core import outbox
from apps.core.outbox import run_after_commit
from apps.jobs import chunks
from apps.jobs.machine import InvalidTransition, StaleTransition, transition
from apps.jobs.models import AppliedRow, EndorsementJob, PremiumLine
from apps.jobs.states import State

from .apply import ConsistencyError, apply_chunk
from .pricing import RosterMismatch, price_chunk

log = logging.getLogger(__name__)
PRICE, ENDORSE = "PREMIUM", "ENDORSE"
MAX_ATTEMPTS = 6
MAX_CD_ATTEMPTS = 5


# ── premium ───────────────────────────────────────────────────────────────────────────────────────────────
@shared_task(name="apps.computation.tasks.start_premium_calculation")
def start_premium_calculation(job_id: str, actor: str = "SYSTEM"):
    """VALIDATION_PASSED / VALIDATION_PARTIAL -> PREMIUM_CALC_QUEUED, then one pricing task per chunk."""
    job = EndorsementJob.objects.get(pk=job_id)
    if job.status not in (State.VALIDATION_PASSED.name, State.VALIDATION_PARTIAL.name, State.PREMIUM_CALC_FAILED.name):
        return "skipped"
    chunks.reset_stage(job_id, PRICE)
    PremiumLine.objects.filter(job_id=job_id).delete()
    EndorsementJob.objects.filter(pk=job_id).update(chunks_priced=0)
    transition(job_id, State.PREMIUM_CALC_QUEUED, actor=actor)
    chunks.ensure_stage(job_id, PRICE, job.chunk_count)
    run_after_commit(lambda: [price_chunk_task.apply_async(args=[job_id, i]) for i in chunks.stage_indexes(job_id, PRICE)])
    return "queued"


def _price_failed(job_id, idx, reason: str) -> None:
    chunks.mark_failed(job_id, idx, PRICE, reason)
    try:
        transition(job_id, State.PREMIUM_CALC_FAILED, expected_from=State.PREMIUM_CALC_QUEUED, metadata={"reason": reason[:500]})
        EndorsementJob.objects.filter(pk=job_id).update(error_summary={"premium_error": reason[:2000]})
    except (InvalidTransition, StaleTransition):
        pass                                                   # another chunk already failed the job


@shared_task(name="apps.computation.tasks.price_chunk", acks_late=True, reject_on_worker_lost=True)
def price_chunk_task(job_id: str, chunk_index: int):
    if not chunks.claim(job_id, chunk_index, PRICE):
        return "skipped"
    job = EndorsementJob.objects.get(pk=job_id)
    if job.status != State.PREMIUM_CALC_QUEUED.name:
        return "job-not-pricing"
    try:
        lines = price_chunk(job, chunk_index, get_policy_client())
    except PolicyUnavailable as e:
        n = chunks.attempts(job_id, chunk_index, PRICE)
        if n >= MAX_ATTEMPTS:
            _price_failed(job_id, chunk_index, f"policy-service unavailable after {n} attempts: {e}")
            return "failed"
        chunks.release(job_id, chunk_index, PRICE, str(e))
        price_chunk_task.apply_async(args=[job_id, chunk_index], countdown=min(2 ** n, 60))
        return "retry-scheduled"
    except (PolicyRejected, PolicyNotFound, PolicyAccessError, RosterMismatch) as e:
        _price_failed(job_id, chunk_index, f"{type(e).__name__}: {e}")
        return "failed"

    with transaction.atomic():
        PremiumLine.objects.bulk_create([PremiumLine(
            job_id=job_id, chunk_index=chunk_index, employee_code=l.employee_code, effective_date=l.effective_date,
            before_annual=l.before, after_annual=l.after, pro_rata_factor=l.factor, charged=l.charged, tax_rate=l.tax_rate,
            tax=l.tax, total=l.total, member_premiums=l.member_premiums,
            employee_charged=l.employee_charged, employer_charged=l.employer_charged) for l in lines], batch_size=1000)
        last = chunks.complete(job_id, chunk_index, PRICE, counter="chunks_priced", rows=len(lines))
    if last:
        finalize_pricing(job_id)
    return "done"


def finalize_pricing(job_id) -> None:
    agg = PremiumLine.objects.filter(job_id=job_id).aggregate(c=Sum("charged"), t=Sum("tax"), tot=Sum("total"))
    EndorsementJob.objects.filter(pk=job_id).update(
        premium_delta=agg["c"] or Decimal(0), tax_amount=agg["t"] or Decimal(0), total_premium=agg["tot"] or Decimal(0))
    try:
        transition(job_id, State.PREMIUM_CALCULATED, expected_from=State.PREMIUM_CALC_QUEUED,
                   metadata={"premium_delta": str(agg["c"] or 0), "total": str(agg["tot"] or 0)})
    except (InvalidTransition, StaleTransition):
        return
    run_after_commit(lambda: check_cd_balance.apply_async(args=[str(job_id)]))


# ── CD balance ────────────────────────────────────────────────────────────────────────────────────────────
@shared_task(name="apps.computation.tasks.check_cd_balance", acks_late=True, reject_on_worker_lost=True)
def check_cd_balance(job_id: str, actor: str = "SYSTEM"):
    job = EndorsementJob.objects.get(pk=job_id)
    if job.status == State.PREMIUM_CALCULATED.name:
        job = transition(job_id, State.CD_CHECK_INITIATED, expected_from=State.PREMIUM_CALCULATED, actor=actor)
    elif job.status != State.CD_CHECK_INITIATED.name:
        return "skipped"

    required = max(job.total_premium or Decimal(0), Decimal(0))      # a refund needs no deposit
    if required == 0:
        status, available = "SUFFICIENT", Decimal(0)
        meta = {"skipped": "no deposit required (premium delta <= 0)"}
    else:
        try:
            res = get_cd_client().check(job.broker_id, job.policy_id, required, str(job.id))
        except CdUnavailable as e:
            n = job.retry_count + 1
            EndorsementJob.objects.filter(pk=job_id).update(retry_count=n)
            if n >= MAX_CD_ATTEMPTS:
                transition(job_id, State.CD_CHECK_FAILED, expected_from=State.CD_CHECK_INITIATED, metadata={"reason": str(e)[:500]})
                return "failed"
            check_cd_balance.apply_async(args=[job_id], countdown=min(2 ** n, 60))
            return "retry-scheduled"
        status, available = res.status, res.available
        meta = {"required": str(required), "available": str(available)}

    EndorsementJob.objects.filter(pk=job_id).update(cd_status=status, retry_count=0)
    target = {"SUFFICIENT": State.CD_SUFFICIENT, "PARTIAL_SUFFICIENT": State.CD_PARTIAL_SUFFICIENT,
              "INSUFFICIENT": State.CD_INSUFFICIENT}[status]
    transition(job_id, target, expected_from=State.CD_CHECK_INITIATED, metadata=meta)
    if target == State.CD_SUFFICIENT:
        queue_endorsement(job_id)
    return status


# ── endorse ───────────────────────────────────────────────────────────────────────────────────────────────
def queue_endorsement(job_id) -> None:
    job = EndorsementJob.objects.get(pk=job_id)
    transition(job_id, State.ENDORSEMENT_QUEUED, expected_from=State.CD_SUFFICIENT)
    chunks.reset_stage(job_id, ENDORSE)
    EndorsementJob.objects.filter(pk=job_id).update(chunks_endorsed=0)
    chunks.ensure_stage(job_id, ENDORSE, job.chunk_count)
    run_after_commit(lambda: [apply_chunk_task.apply_async(args=[str(job_id), i]) for i in chunks.stage_indexes(job_id, ENDORSE)])


def _endorse_failed(job_id, idx, reason: str) -> None:
    chunks.mark_failed(job_id, idx, ENDORSE, reason)
    try:
        transition(job_id, State.ENDORSEMENT_IN_PROGRESS, expected_from=State.ENDORSEMENT_QUEUED)
    except (InvalidTransition, StaleTransition):
        pass
    try:
        transition(job_id, State.ENDORSEMENT_FAILED, expected_from=State.ENDORSEMENT_IN_PROGRESS, metadata={"reason": reason[:500]})
    except (InvalidTransition, StaleTransition):
        pass


@shared_task(name="apps.computation.tasks.apply_chunk", acks_late=True, reject_on_worker_lost=True)
def apply_chunk_task(job_id: str, chunk_index: int):
    if not chunks.claim(job_id, chunk_index, ENDORSE):
        return "skipped"
    job = EndorsementJob.objects.get(pk=job_id)
    if job.status not in (State.ENDORSEMENT_QUEUED.name, State.ENDORSEMENT_IN_PROGRESS.name):
        return "job-not-endorsing"
    try:
        transition(job_id, State.ENDORSEMENT_IN_PROGRESS, expected_from=State.ENDORSEMENT_QUEUED)
    except StaleTransition:
        pass
    try:
        n = apply_chunk(job, chunk_index)
    except ConsistencyError as e:
        _endorse_failed(job_id, chunk_index, f"roster changed since validation: {e}")
        return "failed"
    except Exception as e:                                         # constraint races, DB errors: stop and surface
        log.exception("apply_chunk failed for job %s chunk %s", job_id, chunk_index)
        _endorse_failed(job_id, chunk_index, f"{type(e).__name__}: {e}")
        return "failed"
    with transaction.atomic():
        last = chunks.complete(job_id, chunk_index, ENDORSE, counter="chunks_endorsed", rows=n)
    if last:
        finalize_endorsement(job_id)
    return "done"


def finalize_endorsement(job_id) -> None:
    job = EndorsementJob.objects.get(pk=job_id)
    changed = AppliedRow.objects.filter(job_id=job_id).count()
    with transaction.atomic():           # the state change and the business event commit together
        transition(job_id, State.ENDORSED, expected_from=State.ENDORSEMENT_IN_PROGRESS, metadata={"members_changed": changed})
        outbox.publish("EndorsementJob", job.id, "EndorsementApplied", settings.EVENTS_TOPIC, job.broker_id, {
            "job_id": str(job.id), "broker_id": job.broker_id, "employer_id": job.employer_id, "policy_id": job.policy_id,
            "op_type": job.op_type, "source_type": job.source_type, "members_changed": changed,
            "premium_delta": str(job.premium_delta), "tax_amount": str(job.tax_amount), "total": str(job.total_premium),
            "correlation_id": job.correlation_id,
        }, correlation_id=job.correlation_id)
