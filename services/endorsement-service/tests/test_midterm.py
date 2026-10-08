from datetime import date

import pytest

from apps.intake.models import StagedRow
from apps.validation.models import ValidationLog

from .conftest import BROKER_A, EMP_A, client_for, csv_text, upload
from .test_computation_flow import job_from

pytestmark = pytest.mark.django_db(transaction=True)
ROW = csv_text("1,Self,Asha,1985-01-01,F,500000,,")
START, END = date(2020, 1, 1), date(2099, 12, 31)       # a policy that started long ago, so every joiner is mid-term


def go(flow, midterm, *, inception=False, eff="2026-06-01"):
    flow.add_policy(BROKER_A, 100, EMP_A, start=START, end=END, inception=inception, flat=1000, midterm=midterm,
                    construct={"allowed": {"SPOUSE"}, "max_children": 2, "max_members": 6})
    return job_from(upload(client_for(), ROW, effective_date=eff))


def cover_start(job):
    return StagedRow.objects.get(job_id=job.id).normalised_payload["effective_date"]


def rules(job):
    return set(ValidationLog.objects.filter(job_id=job.id).values_list("rule_code", flat=True))


def test_date_of_joining_keeps_the_sheets_date(flow):
    job = go(flow, "DATE_OF_JOINING")
    assert cover_start(job) == "2026-06-01" and "MIDTERM_NOT_ALLOWED" not in rules(job)


def test_date_of_upload_starts_cover_on_the_upload_day(flow):
    job = go(flow, "DATE_OF_UPLOAD")
    assert cover_start(job) == job.created_at.date().isoformat()


def test_date_of_upload_never_starts_cover_before_the_policy_and_leaves_inception_alone(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, start=date(2099, 4, 1), end=date(2100, 3, 31), flat=1000, midterm="DATE_OF_UPLOAD",
                    construct={"allowed": {"SPOUSE"}, "max_children": 2, "max_members": 6})
    assert cover_start(job_from(upload(client_for(), ROW, effective_date="2099-05-01"))) == "2099-04-01"
    job = go(flow, "DATE_OF_UPLOAD", inception=True)
    assert cover_start(job) == "2026-06-01"


def test_no_midterm_rejects_joiners_after_the_policy_started(flow):
    job = go(flow, "NO_MIDTERM")
    assert "MIDTERM_NOT_ALLOWED" in rules(job) and job.status.startswith("VALIDATION")
    job.refresh_from_db()
    assert job.status in ("VALIDATION_FAILED", "VALIDATION_PARTIAL")


def test_no_midterm_allows_the_policy_start_day_and_inception_windows(flow):
    assert "MIDTERM_NOT_ALLOWED" not in rules(go(flow, "NO_MIDTERM", eff=START.isoformat()))
    assert "MIDTERM_NOT_ALLOWED" not in rules(go(flow, "NO_MIDTERM", inception=True))
