import asyncio
import json

import pytest
from django.test import AsyncClient

from apps.jobs.models import EndorsementJob
from apps.jobs.states import State

from .conftest import BROKER_A, BROKER_B, EMP_A, make_token

pytestmark = pytest.mark.django_db(transaction=True)


async def mk(make_job, **kw):
    """The job factory is synchronous ORM code; run it off the event loop."""
    from asgiref.sync import sync_to_async
    return await sync_to_async(make_job)(**kw)


def url(job, gw="broker"):
    return f"/api/v1/{gw}/endorsements/{job.id}/progress/"


def auth(role="BROKER_ADMIN", broker=BROKER_A, employer=None):
    return {"headers": {"Authorization": f"Bearer {make_token(role, broker, employer)}"}}


def parse(raw: str):
    """-> list of (event, data) in order, ignoring comments and the retry line."""
    out = []
    for block in raw.split("\n\n"):
        ev, data = None, None
        for line in block.splitlines():
            if line.startswith("event:"):
                ev = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:])
        if ev:
            out.append((ev, data))
    return out


async def collect(resp, limit=10.0):
    chunks = []

    async def run():
        async for c in resp.streaming_content:
            chunks.append(c.decode())

    await asyncio.wait_for(run(), timeout=limit)
    return "".join(chunks)


@pytest.fixture(autouse=True)
async def _fast(settings):
    settings.SSE_POLL_SECONDS = 0.05
    settings.SSE_HEARTBEAT_SECONDS = 1
    yield
    # async ORM calls run on a worker thread with its own connection; close it or the test database cannot be dropped
    from asgiref.sync import sync_to_async
    from django.db import connections
    await sync_to_async(connections.close_all)()


async def test_a_finished_job_streams_its_state_and_closes(make_job):
    job = await mk(make_job, status="ENDORSED", status_code=520, phase="EXECUTION", category="SUCCESS", chunk_count=2,
                   chunks_completed=2, chunks_priced=2, chunks_endorsed=2, total_rows=40)
    resp = await AsyncClient().get(url(job), **auth())
    assert resp.status_code == 200 and resp["Content-Type"] == "text/event-stream" and resp["Cache-Control"] == "no-cache"
    events = parse(await collect(resp))
    assert [e for e, _ in events] == ["progress", "rest"]
    assert events[0][1]["state"] == "ENDORSED" and events[0][1]["percent"] == 100 and events[0][1]["total_rows"] == 40
    assert events[1][1]["reason"] == "settled"


async def test_a_live_job_streams_each_change_once_then_rests(make_job):
    job = await mk(make_job, status="VALIDATION_IN_PROGRESS", status_code=210, phase="VALIDATION", category="PROCESSING",
                   chunk_count=4, total_rows=200)
    resp = await AsyncClient().get(url(job), **auth())

    async def advance():
        for n in (1, 2, 4):
            await asyncio.sleep(0.2)
            await EndorsementJob.objects.filter(pk=job.pk).aupdate(chunks_completed=n)
        await asyncio.sleep(0.2)
        s = State.VALIDATION_FAILED
        await EndorsementJob.objects.filter(pk=job.pk).aupdate(status=s.name, status_code=s.code, is_terminal=True,
                                                               category="ERROR")

    worker = asyncio.create_task(advance())
    events = parse(await collect(resp))
    await worker
    progress = [d for e, d in events if e == "progress"]
    assert [p["chunks_validated"] for p in progress] == [0, 1, 2, 4, 4]
    assert [p["percent"] for p in progress][:4] == [0, 8, 16, 33]                       # 1/12, 2/12, 4/12 of the work
    assert len({json.dumps({k: v for k, v in p.items() if k != "elapsed_ms"}, sort_keys=True) for p in progress}) == len(progress)
    assert events[-1][0] == "rest" and events[-1][1]["reason"] == "terminal"


@pytest.mark.parametrize("state,reason", [("VALIDATION_PARTIAL", "waiting_for_human"), ("CD_INSUFFICIENT", "waiting_for_human"),
                                          ("PREMIUM_CALC_FAILED", "waiting_for_human"), ("CANCELLED_BY_HR", "terminal")])
async def test_it_closes_when_a_human_must_act(make_job, state, reason):
    s = State[state]
    job = await mk(make_job, status=s.name, status_code=s.code, phase=s.phase.value, category=s.category.value, is_terminal=s.terminal)
    events = parse(await collect(await AsyncClient().get(url(job), **auth())))
    assert events[-1][0] == "rest" and events[-1][1]["reason"] == reason


async def test_idle_streams_send_keepalives_and_time_out_cleanly(make_job, settings):
    settings.SSE_MAX_SECONDS = 2
    job = await mk(make_job, status="VALIDATION_IN_PROGRESS", status_code=210, phase="VALIDATION", category="PROCESSING", chunk_count=1)
    raw = await collect(await AsyncClient().get(url(job), **auth()))
    assert ": keepalive" in raw
    events = parse(raw)
    assert events[-1] == ("timeout", {"reconnect": True}) and [e for e, _ in events].count("progress") == 1   # nothing changed


async def test_auth_and_tenancy_are_enforced(make_job):
    job = await mk(make_job, status="VALIDATION_IN_PROGRESS", status_code=210)
    client = AsyncClient()
    assert (await client.get(url(job))).status_code == 401
    assert (await client.get(url(job), headers={"Authorization": "Bearer nope"})).status_code == 401
    assert (await client.get(url(job), **auth("BROKER_ADMIN", BROKER_B))).status_code == 404          # other broker
    assert (await client.get(url(job, "employer"), **auth("EMPLOYER_ADMIN", BROKER_A, EMP_A + 9))).status_code == 404   # other employer
    assert (await client.get(url(job), **auth("EMPLOYER_ADMIN", BROKER_A, EMP_A))).status_code == 403   # wrong gateway for role
    assert (await client.get(url(job, "employer"), **auth("BROKER_ADMIN"))).status_code == 403
    assert (await client.get(url(job, "tpa"), **auth("TPA_USER", None))).status_code in (403, 404)
    assert (await client.get(url(job), **auth("BROKER_NON_ADMIN"))).status_code == 200                # read-only roles may watch
    other_id = f"/api/v1/broker/endorsements/00000000-0000-0000-0000-000000000000/progress/"
    assert (await client.get(other_id, **auth())).status_code == 404
