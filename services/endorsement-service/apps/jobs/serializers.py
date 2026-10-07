from .models import AuditTransition, EndorsementJob


def _money(v):
    """Money crosses the API as a string: JSON numbers are floats and floats lose cents."""
    return None if v is None else str(v)


def job_to_dict(j: EndorsementJob) -> dict:
    return {
        "id": str(j.id), "policy_id": j.policy_id, "employer_id": j.employer_id, "broker_id": j.broker_id,
        "source_type": j.source_type, "op_type": j.op_type,
        "status": j.status, "status_code": j.status_code, "phase": j.phase, "category": j.category,
        "is_terminal": j.is_terminal, "correlation_id": j.correlation_id,
        "source_filename": j.source_filename, "effective_date": j.effective_date,
        "total_rows": j.total_rows, "failed_rows": j.failed_rows,
        "chunk_count": j.chunk_count, "chunks_completed": j.chunks_completed,
        "total_premium": _money(j.total_premium), "tax_amount": _money(j.tax_amount),
        "premium_delta": _money(j.premium_delta),
        "cd_status": j.cd_status, "error_summary": j.error_summary,
        "created_at": j.created_at, "started_at": j.started_at, "completed_at": j.completed_at,
    }


def audit_to_dict(a: AuditTransition) -> dict:
    return {"from_state": a.from_state, "to_state": a.to_state, "phase": a.phase, "category": a.category,
            "actor": a.actor, "duration_ms": a.duration_ms, "metadata": a.metadata,
            "transitioned_at": a.transitioned_at}
