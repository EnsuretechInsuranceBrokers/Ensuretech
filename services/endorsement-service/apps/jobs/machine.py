"""The only code allowed to change a job's status. Every change is guarded, audited and evented atomically."""
import logging
from datetime import UTC, datetime

from django.conf import settings
from django.db import transaction

from apps.core import outbox

from .models import AuditTransition, EndorsementJob
from .states import TRANSITIONS, State

log = logging.getLogger(__name__)


class InvalidTransition(Exception):
    pass


class StaleTransition(Exception):
    """The job is no longer in the state the caller expected (a concurrent actor moved it)."""


def transition(job_id, to: State, *, actor: str = "SYSTEM", actor_ip: str | None = None,
               expected_from: State | None = None, metadata: dict | None = None) -> EndorsementJob:
    """Move a job to ``to``.

    * Row-locked, so concurrent transitions serialise.
    * ``expected_from`` makes redelivered/duplicate tasks safe: if the job already reached ``to`` it is a no-op.
    * Illegal moves and moves out of terminal states raise InvalidTransition.
    """
    with transaction.atomic():
        job = EndorsementJob.objects.select_for_update().get(pk=job_id)
        current = State[job.status]

        if expected_from is not None and current != expected_from:
            if current == to:
                return job                      # already done: idempotent redelivery
            raise StaleTransition(f"{job.id}: expected {expected_from.name}, found {current.name}")
        if current == to and expected_from is None:
            return job
        if current.terminal:
            raise InvalidTransition(f"{current.name} is terminal")
        if to not in TRANSITIONS[current]:
            raise InvalidTransition(f"{current.name} -> {to.name} is not allowed")

        now = datetime.now(UTC)
        spent_ms = int((now - job.updated_at).total_seconds() * 1000)
        job.status, job.status_code = to.name, to.code
        job.phase, job.category, job.is_terminal = to.phase.value, to.category.value, to.terminal
        if job.started_at is None and to.code >= State.VALIDATION_QUEUED.code:
            job.started_at = now
        if to.terminal:
            job.completed_at = now
        job.save()

        AuditTransition.objects.create(
            job=job, correlation_id=job.correlation_id, from_state=current.name, to_state=to.name,
            from_code=current.code, to_code=to.code, phase=to.phase.value, category=to.category.value,
            is_terminal=to.terminal, actor=actor, actor_ip=actor_ip or None, broker_id=job.broker_id,
            employer_id=job.employer_id, policy_id=job.policy_id, source_type=job.source_type,
            duration_ms=spent_ms, metadata=metadata or {})

        outbox.publish("EndorsementJob", job.id, "EndorsementStateChanged", settings.EVENTS_TOPIC, job.broker_id, {
            "job_id": str(job.id), "from_state": current.name, "to_state": to.name, "phase": to.phase.value,
            "category": to.category.value, "is_terminal": to.terminal, "actor": actor,
            "broker_id": job.broker_id, "employer_id": job.employer_id, "policy_id": job.policy_id,
            "source_type": job.source_type, "op_type": job.op_type, "correlation_id": job.correlation_id,
            "timestamp": now.isoformat(),
        }, correlation_id=job.correlation_id)
        return job
