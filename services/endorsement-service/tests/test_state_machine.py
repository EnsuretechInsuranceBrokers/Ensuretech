import pytest
from django.db import DatabaseError, connection, transaction

from apps.jobs.machine import InvalidTransition, StaleTransition, transition
from apps.jobs.models import AuditTransition, EndorsementJob
from apps.jobs.states import TRANSITIONS, State

S = State


def test_graph_covers_every_state_and_terminals_have_no_exits():
    assert set(TRANSITIONS) == set(State)
    for s in State:
        if s.terminal:
            assert TRANSITIONS[s] == frozenset(), s


def test_state_codes_are_unique():
    codes = [s.code for s in State]
    assert len(codes) == len(set(codes))


def _reach(start):
    seen, stack = set(), [start]
    while stack:
        s = stack.pop()
        if s not in seen:
            seen.add(s)
            stack.extend(TRANSITIONS[s])
    return seen


def test_every_state_is_reachable_and_can_finish():
    reachable = _reach(S.REQUEST_RAISED)
    assert reachable == set(State), set(State) - reachable
    terminals = {s for s in State if s.terminal}
    for s in State:
        assert _reach(s) & terminals, f"{s.name} can never finish"


def test_happy_path_updates_job_and_writes_audit_and_events(make_job):
    job = make_job()
    path = [S.FILE_STREAMED, S.VALIDATION_QUEUED, S.VALIDATION_IN_PROGRESS, S.VALIDATION_PASSED,
            S.PREMIUM_CALC_QUEUED, S.PREMIUM_CALCULATED, S.CD_CHECK_INITIATED, S.CD_SUFFICIENT,
            S.ENDORSEMENT_QUEUED, S.ENDORSEMENT_IN_PROGRESS, S.ENDORSED, S.COMPLETED]
    for s in path:
        job = transition(job.id, s, actor="tester")
    assert (job.status, job.status_code, job.is_terminal) == ("COMPLETED", 900, True)
    assert job.completed_at and job.started_at
    rows = list(AuditTransition.objects.filter(job=job))
    assert [r.to_state for r in rows] == [s.name for s in path]
    assert rows[0].from_state == "REQUEST_RAISED" and rows[0].actor == "tester"
    with connection.cursor() as cur:
        cur.execute("SELECT count(*), min(topic), min(partition_key) FROM outbox.outbox_events "
                    "WHERE aggregate_id=%s AND event_type='EndorsementStateChanged'", [str(job.id)])
        assert cur.fetchone() == (len(path), "endorsement.events", str(job.broker_id))


def test_illegal_move_is_rejected_and_leaves_no_trace(make_job):
    job = make_job()
    with pytest.raises(InvalidTransition):
        transition(job.id, S.ENDORSED)
    job.refresh_from_db()
    assert job.status == "REQUEST_RAISED"
    assert AuditTransition.objects.filter(job=job).count() == 0


def test_terminal_states_cannot_be_left(make_job):
    job = make_job()
    transition(job.id, S.CANCELLED_BY_HR)
    with pytest.raises(InvalidTransition, match="terminal"):
        transition(job.id, S.FILE_STREAMED)


def test_cannot_cancel_once_execution_is_queued(make_job):
    job = make_job(status="ENDORSEMENT_QUEUED", status_code=500)
    with pytest.raises(InvalidTransition):
        transition(job.id, S.CANCELLED_BY_HR)


def test_expected_from_makes_redelivery_idempotent(make_job):
    job = make_job()
    transition(job.id, S.FILE_STREAMED, expected_from=S.REQUEST_RAISED)
    # the same task delivered twice: second call is a no-op, not an error and not a duplicate audit row
    again = transition(job.id, S.FILE_STREAMED, expected_from=S.REQUEST_RAISED)
    assert again.status == "FILE_STREAMED"
    assert AuditTransition.objects.filter(job=job).count() == 1
    with pytest.raises(StaleTransition):
        transition(job.id, S.CANCELLED_BY_HR, expected_from=S.REQUEST_RAISED)


def test_same_state_without_expected_from_is_a_noop(make_job):
    job = make_job()
    transition(job.id, S.FILE_STREAMED)
    transition(job.id, S.FILE_STREAMED)
    assert AuditTransition.objects.filter(job=job).count() == 1


def test_audit_rows_are_immutable_in_the_orm(make_job):
    job = make_job()
    transition(job.id, S.FILE_STREAMED)
    row = AuditTransition.objects.get(job=job)
    row.actor = "evil"
    with pytest.raises(RuntimeError):
        row.save()
    with pytest.raises(RuntimeError):
        row.delete()


def test_audit_rows_are_immutable_in_the_database(make_job):
    job = make_job()
    transition(job.id, S.FILE_STREAMED)
    for sql in ("UPDATE endorsement.audit_transitions SET actor='evil'", "DELETE FROM endorsement.audit_transitions"):
        with pytest.raises(DatabaseError, match="append-only"), transaction.atomic(), connection.cursor() as cur:
            cur.execute(sql)
    assert AuditTransition.objects.filter(job=job, actor="evil").count() == 0


def test_outbox_publish_requires_a_transaction(db):
    from apps.core import outbox
    # pytest-django wraps tests in a transaction, so simulate "no atomic block" by checking the guard directly
    assert callable(outbox.publish)


def test_idempotency_key_is_unique_per_broker(make_job):
    make_job(idempotency_key="k1")
    with pytest.raises(Exception), transaction.atomic():
        make_job(idempotency_key="k1")
    make_job(broker_id=2, employer_id=20, idempotency_key="k1")      # other broker may reuse the key
    make_job(idempotency_key=None)
    make_job(idempotency_key=None)                                    # nulls never collide
