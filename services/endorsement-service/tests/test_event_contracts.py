"""The engine's REAL events must keep exactly the shape in shared/contracts/events (billing-service tests feed those same
files to its real handlers). If a field changes here, this fails until the sample, and therefore the consumer, follows."""
import json
from pathlib import Path

import pytest
from django.db import connection

from apps.jobs.models import EndorsementJob

from .conftest import client_for, csv_text, upload

pytestmark = pytest.mark.django_db(transaction=True)
SAMPLES = Path(__file__).resolve().parents[3] / "shared" / "contracts" / "events"


def sample(name):
    return json.loads((SAMPLES / f"{name}.sample.json").read_text())


def events_for(job, etype):
    with connection.cursor() as cur:
        cur.execute("SELECT payload FROM outbox.outbox_events WHERE aggregate_id=%s AND event_type=%s ORDER BY created_at", [str(job.id), etype])
        return [json.loads(r[0]) if isinstance(r[0], str) else r[0] for r in cur.fetchall()]


def shape(d):
    return {k: type(v).__name__ for k, v in d.items()}


def test_endorsement_applied_matches_the_shared_sample(flow):
    r = upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01")
    job = EndorsementJob.objects.get(pk=r.json()["job_id"])
    real = events_for(job, "EndorsementApplied")[0]
    assert shape(real) == shape(sample("endorsement-applied")), "field names or types drifted from the contract"
    for money in ("premium_delta", "tax_amount", "total"):
        assert isinstance(real[money], str)                                  # money is a decimal string, never a float
    assert real["event_type"] == "EndorsementApplied" and real["total"] == "1180.00"


def test_state_changed_matches_the_shared_sample(flow, settings):
    settings.PARTIAL_AUTO_CONTINUE = False                  # keep the partly failed upload held so there is something to cancel
    r = upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,", "2,Self,,1990-01-01,M,500000,,"), effective_date="2030-04-01")
    job = EndorsementJob.objects.get(pk=r.json()["job_id"])
    client_for().post(f"/api/v1/broker/endorsements/{job.id}/cancel/")
    real = [e for e in events_for(job, "EndorsementStateChanged") if e["to_state"] == "CANCELLED_BY_HR"][0]
    assert shape(real) == shape(sample("endorsement-state-changed"))
    assert real["is_terminal"] is True and real["to_state"] == "CANCELLED_BY_HR"
