import io

import openpyxl
import pytest

from apps.jobs.models import EndorsementJob, EndorsementMember

from .conftest import BROKER_B, client_for, csv_text, upload

pytestmark = pytest.mark.django_db(transaction=True)
BASE = "/api/v1/broker/endorsements/"
XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
MIXED = csv_text("1,Self,Good,1990-01-01,M,500000,,", "2,Self,,1990-01-01,M,500000,,", "3,Self,Also Good,1990-01-01,M,500000,,")


def job_from(resp):
    assert resp.status_code in (200, 202), resp.content
    return EndorsementJob.objects.get(pk=resp.json()["job_id"])


def sheet(resp):
    return openpyxl.load_workbook(io.BytesIO(resp.content))


def test_a_partly_failed_upload_carries_on_with_the_valid_rows_by_itself(flow):
    job = job_from(upload(client_for(), MIXED, effective_date="2030-04-01"))
    assert job.status == "ENDORSED"
    assert sorted(EndorsementMember.objects.values_list("employee_code", flat=True)) == ["1", "3"]
    assert job.failed_rows == 1 and job.total_rows == 3                                         # the failure is still on the record
    errs = client_for().get(f"{BASE}{job.id}/errors/").json()
    assert errs["count"] == 1 and errs["results"][0]["employee_code"] == "2"


def test_it_can_still_be_held_for_a_person_when_the_setting_is_off(flow, settings):
    settings.PARTIAL_AUTO_CONTINUE = False
    job = job_from(upload(client_for(), MIXED, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PARTIAL" and EndorsementMember.objects.count() == 0


def test_a_fully_failed_upload_stops_and_does_not_continue(flow):
    job = job_from(upload(client_for(), csv_text("1,Self,,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    assert job.status == "VALIDATION_FAILED" and EndorsementMember.objects.count() == 0


def test_the_error_sheet_lists_the_rows_to_fix_and_every_finding(flow):
    job = job_from(upload(client_for(), MIXED, effective_date="2030-04-01"))
    r = client_for().get(f"{BASE}{job.id}/errors/download/")
    assert r.status_code == 200 and r["Content-Type"] == XLSX
    assert r["Content-Disposition"].startswith('attachment; filename="') and r["Content-Disposition"].endswith('_errors.xlsx"')
    wb = sheet(r)
    assert wb.sheetnames == ["Rows to fix", "All findings"]
    rows = list(wb["Rows to fix"].iter_rows(values_only=True))
    header = list(rows[0])
    assert header[0] == "Line" and "Employee Code" in header and header[-2:] == ["Problems", "Rules"]
    assert len(rows) == 2                                                                       # one bad line, so one row to fix
    bad = dict(zip(header, rows[1]))
    assert bad["Employee Code"] == "2" and bad["Problems"] and bad["Rules"]
    findings = list(wb["All findings"].iter_rows(values_only=True))
    assert findings[0][:4] == ("Line", "Employee Code", "Field", "Rule") and len(findings) >= 2


def test_a_clean_job_gives_an_empty_error_sheet(flow):
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,"), effective_date="2030-04-01"))
    wb = sheet(client_for().get(f"{BASE}{job.id}/errors/download/"))
    assert len(list(wb["Rows to fix"].iter_rows(values_only=True))) == 1                        # the header only


def test_values_that_look_like_formulas_are_written_as_text(flow):
    text = csv_text("1,Self,=HYPERLINK(\"http://x\"),1990-01-01,M,500000,,", "2,Self,Fine,1990-01-01,M,500000,,")
    job = job_from(upload(client_for(), text, effective_date="2030-04-01"))
    wb = sheet(client_for().get(f"{BASE}{job.id}/errors/download/"))
    for sh in wb:
        for row in sh.iter_rows(values_only=True):
            assert not any(isinstance(v, str) and v.startswith("=") for v in row), row


def test_another_broker_cannot_download_it_and_a_stranger_gets_nothing(flow):
    job = job_from(upload(client_for(), MIXED, effective_date="2030-04-01"))
    assert client_for("BROKER_ADMIN", BROKER_B).get(f"{BASE}{job.id}/errors/download/").status_code == 404
    from rest_framework.test import APIClient
    assert APIClient().get(f"{BASE}{job.id}/errors/download/").status_code in (401, 403)
