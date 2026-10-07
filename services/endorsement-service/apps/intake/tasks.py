import logging

from celery import shared_task
from django.db import OperationalError, transaction

from apps.core.outbox import run_after_commit
from apps.jobs.machine import transition
from apps.jobs.models import EndorsementJob
from apps.jobs.states import State
from apps.validation.models import ChunkState
from apps.validation.tasks import dispatch_validation

from .models import SchemaMapping, StagedRow
from .parser import FileProblem, iter_chunks
from .store import get_store

log = logging.getLogger(__name__)


def _fail(job_id, reason: str) -> None:
    StagedRow.objects.filter(job_id=job_id).delete()
    ChunkState.objects.filter(job_id=job_id).delete()
    EndorsementJob.objects.filter(pk=job_id).update(error_summary={"ingest_error": reason})
    transition(job_id, State.VALIDATION_FAILED, metadata={"ingest_error": reason})


@shared_task(bind=True, name="apps.intake.tasks.ingest_csv", acks_late=True, autoretry_for=(OperationalError,),
             retry_backoff=True, max_retries=3)
def ingest_csv(self, job_id: str):
    """Stream the stored file into staging chunks, then queue validation. Safe to redeliver: a half-staged job
    is wiped and re-ingested; a job past REQUEST_RAISED is left alone."""
    from django.conf import settings

    job = EndorsementJob.objects.get(pk=job_id)
    if job.status != State.REQUEST_RAISED.name:
        return "skipped"
    StagedRow.objects.filter(job_id=job_id).delete()
    ChunkState.objects.filter(job_id=job_id).delete()

    mapping = SchemaMapping.objects.get(pk=job.schema_mapping_id)
    store, key = get_store(), job.metadata["file_key"]
    rows = chunks = 0
    try:
        with store.open(key) as f:
            for chunk in iter_chunks(f, column_map=mapping.column_map, op_type=job.op_type,
                                     source_key=mapping.source_key, options=mapping.options,
                                     default_effective_date=job.effective_date.isoformat() if job.effective_date else None,
                                     target_rows=settings.CHUNK_TARGET_ROWS, max_rows=settings.MAX_UPLOAD_ROWS):
                with transaction.atomic():
                    StagedRow.objects.bulk_create([
                        StagedRow(job_id=job_id, chunk_index=chunk.index, row_index=rows + n, source_line=r.source_line,
                                  op_type=job.op_type, raw_payload=r.raw,
                                  normalised_payload={**r.normalised, "_errors": r.errors}, row_hash=r.row_hash)
                        for n, r in enumerate(chunk.rows)], batch_size=1000)
                    ChunkState.objects.create(job_id=job_id, chunk_index=chunk.index, stage="VALIDATE",
                                              row_count=len(chunk.rows))
                rows += len(chunk.rows)
                chunks += 1
    except FileProblem as e:
        _fail(job_id, str(e))
        store.delete(key)
        return "failed"
    if rows == 0:
        _fail(job_id, "file has a header but no data rows")
        store.delete(key)
        return "failed"

    EndorsementJob.objects.filter(pk=job_id).update(total_rows=rows, chunk_count=chunks)
    transition(job_id, State.FILE_STREAMED, expected_from=State.REQUEST_RAISED, metadata={"rows": rows, "chunks": chunks})
    transition(job_id, State.VALIDATION_QUEUED, expected_from=State.FILE_STREAMED)
    store.delete(key)
    run_after_commit(lambda: dispatch_validation(job_id))
    return "ingested"


@shared_task(name="apps.intake.tasks.expire_gui_sessions")
def expire_gui_sessions() -> int:
    """Open sessions past their deadline become ABANDONED so stale grids cannot be submitted days later."""
    from django.utils import timezone

    from .models import GuiSession
    return GuiSession.objects.filter(state="OPEN", expires_at__lt=timezone.now()).update(state="ABANDONED")
