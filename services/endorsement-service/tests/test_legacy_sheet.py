"""The legacy add-sheet layout (what the add-sheet generator emits and brokers upload today): one block of Employee* columns for the
Self row and another block of Insured Member* columns for dependants, dates as dd-mm-yyyy text or real Excel dates, .xlsx by default."""
import io
import zipfile
from datetime import date

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from openpyxl import Workbook

from apps.intake.models import StagedRow
from apps.jobs.models import EndorsementJob

from .conftest import client_for

pytestmark = pytest.mark.django_db(transaction=True)

HEADERS = ["Employee Code", "Employee First Name", "Employee Last Name", "Employee Gender", "Employee DOB", "Employee Date of Joining", "Employee Email",
           "Employee Mobile Number", "Relationship with Employee", "Insured Member First Name", "Insured Member Last Name", "Insured Member Gender",
           "Insured Member DOB", "Date of Marriage", "Suminsured", "Should Verify Email", "Default Password", "Employee State"]
ROWS = [
    ["E100001", "Asha", "Rao", "Female", "17-05-1985", "01-06-2030", "e100001@x.com", "9876543210", "Self", None, None, None, None, "10-02-2010", 500000, "No", "Test@12345", "Karnataka"],
    ["E100001", None, None, None, None, None, None, None, "Spouse", "Ravi", "Rao", "Male", "02-02-1983", "10-02-2010", 500000, None, None, None],
    ["E100001", None, None, None, None, None, None, None, "Son", "Kid", "Rao", "Male", date(2015, 1, 1), None, 500000, None, None, None],
]


def xlsx_bytes(headers=HEADERS, rows=ROWS):
    wb = Workbook()
    ws = wb.active
    ws.append(headers)
    for r in rows:
        ws.append(r)
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def post(client, data, name="sheet.xlsx", ctype="application/octet-stream", **extra):
    f = SimpleUploadedFile(name, data, content_type=ctype)
    return client.post("/api/v1/broker/endorsements/csv/upload/", {"file": f, "op_type": "ADD", "policy_id": 100, **extra}, format="multipart")


def staged(job_id):
    return {s.normalised_payload["relation_code"]: s.normalised_payload for s in StagedRow.objects.filter(job_id=job_id)}


def test_an_xlsx_in_the_legacy_layout_is_read_with_each_row_taking_its_own_block(world):
    r = post(client_for(), xlsx_bytes())
    assert r.status_code == 202, r.content
    rows = staged(r.json()["job_id"])
    assert set(rows) == {"SELF", "SPOUSE", "SON"}
    assert (rows["SELF"]["first_name"], rows["SELF"]["dob"], rows["SELF"]["gender"]) == ("Asha", "1985-05-17", "F")
    assert (rows["SPOUSE"]["first_name"], rows["SPOUSE"]["dob"], rows["SPOUSE"]["gender"]) == ("Ravi", "1983-02-02", "M")
    assert rows["SON"]["dob"] == "2015-01-01"                                   # a real Excel date cell
    assert rows["SELF"]["sum_insured"] == "500000.00"                           # 500000 arrives from Excel as a number


def test_date_of_joining_is_the_effective_date_for_the_whole_family_unless_an_effective_date_column_says_otherwise(world):
    rows = staged(post(client_for(), xlsx_bytes()).json()["job_id"])
    assert {r["effective_date"] for r in rows.values()} == {"2030-06-01"}       # the dependants' DOJ cells are blank; they inherit the employee's
    headers = HEADERS + ["Effective Date"]
    r = post(client_for(), xlsx_bytes(headers, [row + ["01-09-2030"] for row in ROWS]))
    assert {x["effective_date"] for x in staged(r.json()["job_id"]).values()} == {"2030-09-01"}


def test_a_dependant_never_silently_borrows_the_employees_dob_or_name(world):
    broken = [list(r) for r in ROWS]
    broken[1][12] = None                                                         # spouse DOB missing
    broken[2][9] = None                                                          # son first name missing
    job = EndorsementJob.objects.get(pk=post(client_for(), xlsx_bytes(rows=broken)).json()["job_id"])
    rows = staged(job.id)
    assert rows["SPOUSE"]["dob"] is None and rows["SON"]["first_name"] is None
    assert job.failed_rows >= 2


def test_extension_must_match_content_and_zip_bombs_are_refused(world):
    c = client_for()
    assert post(c, b"Emp Code,Relationship\n1,Self\n", name="renamed.xlsx").status_code == 400            # CSV pretending to be Excel
    assert post(c, xlsx_bytes(), name="renamed.csv").status_code == 400                                   # Excel pretending to be CSV
    bomb = io.BytesIO()
    with zipfile.ZipFile(bomb, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("xl/worksheets/sheet1.xml", b"0" * (40 * 1024 * 1024))                                 # ~40 MB of zeros -> huge ratio
    r = post(c, bomb.getvalue(), name="bomb.xlsx")
    assert r.status_code == 400 and "unreasonable size" in r.json()["detail"]
    corrupt = post(c, b"PK\x03\x04 this is not a zip", name="bad.xlsx")
    assert corrupt.status_code == 400 and "Excel" in corrupt.json()["detail"]
