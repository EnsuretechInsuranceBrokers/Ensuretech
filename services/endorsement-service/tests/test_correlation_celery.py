from types import SimpleNamespace

from config import celery as celery_config
from config import correlation


def test_a_queued_task_carries_the_requests_id_and_the_worker_logs_under_it():
    correlation.use("qa-trace-task-1")
    headers = {}
    celery_config._carry_correlation_id(headers=headers)
    assert headers["x-correlation-id"] == "qa-trace-task-1"
    correlation.clear()                                                          # the worker is a different process: nothing is inherited
    task = SimpleNamespace(name="apps.x.y", request=SimpleNamespace(args=("job",), get=lambda k, d=None: headers.get(k, d), **{}))
    celery_config._task_starts(task_id="11111111-2222-3333-4444-555555555555", task=task)
    assert correlation.current() == "qa-trace-task-1"
    celery_config._task_ends(task_id="11111111-2222-3333-4444-555555555555", task=task, state="SUCCESS")
    assert correlation.current() is None


def test_a_task_with_no_request_behind_it_gets_its_own_id():
    correlation.clear()
    task = SimpleNamespace(name="apps.core.tasks.relay_outbox", request=SimpleNamespace(args=(), get=lambda k, d=None: d))
    celery_config._task_starts(task_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", task=task)
    assert correlation.current() == "task-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    celery_config._task_ends(task_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", task=task, state="SUCCESS")
