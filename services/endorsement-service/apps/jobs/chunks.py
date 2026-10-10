"""Shared coordination for chunked stages (validate, price, endorse).

Every stage follows the same rules: a chunk is claimed with a compare-and-set (so a redelivered message cannot run
twice), attempts are counted in the database (so they survive lost messages), and exactly one worker - the one whose
completion makes the count reach chunk_count - gets told it is the last.
"""
from datetime import timedelta

from django.db import transaction
from django.db.models import F, Q
from django.utils import timezone

from apps.validation.models import ChunkState, ChunkStatus

from .models import EndorsementJob

STALE_AFTER = timedelta(minutes=10)      # a RUNNING chunk this old belongs to a crashed worker and may be reclaimed


def ensure_stage(job_id, stage: str, chunk_count: int) -> None:
    ChunkState.objects.bulk_create(
        [ChunkState(job_id=job_id, chunk_index=i, stage=stage) for i in range(chunk_count)], ignore_conflicts=True)


def reset_stage(job_id, stage: str) -> None:
    ChunkState.objects.filter(job_id=job_id, stage=stage).delete()


def stage_indexes(job_id, stage: str) -> list[int]:
    return list(ChunkState.objects.filter(job_id=job_id, stage=stage).order_by("chunk_index")
                .values_list("chunk_index", flat=True))


def claim(job_id, idx: int, stage: str) -> bool:
    now = timezone.now()
    n = (ChunkState.objects.filter(job_id=job_id, chunk_index=idx, stage=stage)
         .filter(Q(status__in=[ChunkStatus.PENDING, ChunkStatus.FAILED])
                 | Q(status=ChunkStatus.RUNNING, started_at__lt=now - STALE_AFTER))
         .update(status=ChunkStatus.RUNNING, started_at=now, attempts=F("attempts") + 1))
    return n == 1


def attempts(job_id, idx: int, stage: str) -> int:
    return ChunkState.objects.values_list("attempts", flat=True).get(job_id=job_id, chunk_index=idx, stage=stage)


def release(job_id, idx: int, stage: str, error: str) -> None:
    ChunkState.objects.filter(job_id=job_id, chunk_index=idx, stage=stage).update(
        status=ChunkStatus.PENDING, last_error=error[:500])


def mark_failed(job_id, idx: int, stage: str, reason: str) -> None:
    ChunkState.objects.filter(job_id=job_id, chunk_index=idx, stage=stage).update(
        status=ChunkStatus.FAILED, last_error=reason[:2000], finished_at=timezone.now())


def complete(job_id, idx: int, stage: str, *, counter: str, rows: int = 0, failed: int = 0,
             job_increments: dict | None = None) -> bool:
    """Mark the chunk DONE and bump the job's stage counter under a row lock. Call inside transaction.atomic().
    @return True only for the single call that completes the final chunk."""
    cs = ChunkState.objects.select_for_update().get(job_id=job_id, chunk_index=idx, stage=stage)
    if cs.status == ChunkStatus.DONE:
        return False
    cs.status, cs.row_count, cs.failed_rows, cs.finished_at, cs.last_error = ChunkStatus.DONE, rows, failed, timezone.now(), ""
    cs.save()
    job = EndorsementJob.objects.select_for_update().get(pk=job_id)
    setattr(job, counter, getattr(job, counter) + 1)
    fields = [counter, "updated_at"]
    for name, delta in (job_increments or {}).items():
        setattr(job, name, getattr(job, name) + delta)
        fields.append(name)
    job.save(update_fields=fields)
    return getattr(job, counter) == job.chunk_count


def in_transaction() -> bool:
    return transaction.get_connection().in_atomic_block
