from datetime import date, timedelta

import pytest

from apps.intake.models import StagedRow
from apps.jobs.models import EndorsementMember
from apps.validation.midterm import group_of
from apps.validation.models import ValidationLog

from .conftest import BROKER_A, EMP_A, add_member, client_for, upload
from .test_computation_flow import job_from

pytestmark = pytest.mark.django_db(transaction=True)
TODAY = date.today()
HEADER = "Emp Code,Relationship,Member Name,DOB,Gender,Sum Insured,Effective Date,Date of Marriage"
START, END = date(2020, 1, 1), date(2099, 12, 31)


def csv(*rows):
    return "\n".join([HEADER, *rows]) + "\n"


def run(flow, rules, row, *, covered=True, eff="2026-06-01"):
    flow.add_policy(BROKER_A, 100, EMP_A, start=START, end=END, flat=1000, dependant_midterm=rules,
                    construct={"allowed": {"SPOUSE", "SON", "DOMESTIC_PARTNER"}, "max_children": 3, "max_members": 6})
    if covered and not EndorsementMember.objects.filter(policy_id=100, employee_code="1", relation_code="SELF").exists():
        add_member(code="1", rel="SELF", dob="1985-01-01")
    job = job_from(upload(client_for(), csv(row), effective_date=eff))
    rows = {r.normalised_payload["employee_code"]: r.normalised_payload for r in StagedRow.objects.filter(job_id=job.id)}
    found = list(ValidationLog.objects.filter(job_id=job.id).values_list("rule_code", "severity"))
    return job, rows["1"], found


def ago(days):
    return (TODAY - timedelta(days=days)).isoformat()


def test_relations_map_to_the_legacy_member_groups():
    assert [group_of(r) for r in ("SPOUSE", "WIFE", "HUSBAND", "DOMESTIC_PARTNER", "SON", "DAUGHTER_II", "FATHER", "SELF", "STEP_CHILD", None)] == \
        ["SPOUSE", "SPOUSE", "SPOUSE", "PARTNER", "KIDS", "KIDS", None, None, None, None]


def test_a_spouse_added_mid_term_starts_cover_on_the_date_of_marriage(flow):
    marriage = ago(20)
    _, payload, found = run(flow, {"SPOUSE": {"allowed": True, "window_days": 30, "start_basis": "EVENT"}}, f"1,Spouse,Ravi,1983-02-02,M,500000,,{marriage}")
    assert payload["effective_date"] == marriage and payload["date_of_marriage"] == marriage
    assert ("MIDTERM_APPROVAL_REQUIRED", "WARN") not in found and not [f for f in found if f[1] == "ERROR"]


def test_the_upload_day_basis_ignores_the_marriage_date_but_the_window_still_counts_from_it(flow):
    job, payload, found = run(flow, {"SPOUSE": {"allowed": True, "window_days": 30, "start_basis": "UPLOAD"}}, f"1,Spouse,Ravi,1983-02-02,M,500000,,{ago(90)}")
    assert payload["effective_date"] == job.created_at.date().isoformat()
    assert ("MIDTERM_APPROVAL_REQUIRED", "WARN") in found


def test_a_child_starts_cover_at_birth_and_a_not_allowed_group_is_rejected(flow):
    born = ago(10)
    _, payload, _ = run(flow, {"KIDS": {"allowed": True, "start_basis": "EVENT"}}, f"1,Son,Kid,{born},M,500000,,")
    assert payload["effective_date"] == born
    _, _, found = run(flow, {"KIDS": {"allowed": False, "start_basis": "UPLOAD"}}, f"1,Son,Kid,{born},M,500000,,")
    assert ("MIDTERM_DEPENDANT_NOT_ALLOWED", "ERROR") in found


def test_event_basis_needs_the_date_and_it_cannot_be_in_the_future(flow):
    rules = {"SPOUSE": {"allowed": True, "start_basis": "EVENT"}}
    assert ("MIDTERM_EVENT_DATE_REQUIRED", "ERROR") in run(flow, rules, "1,Spouse,Ravi,1983-02-02,M,500000,,")[2]
    future = (TODAY + timedelta(days=5)).isoformat()
    assert ("MIDTERM_EVENT_IN_FUTURE", "ERROR") in run(flow, rules, f"1,Spouse,Ravi,1983-02-02,M,500000,,{future}")[2]


def test_cover_never_starts_before_the_policy_and_unruled_groups_keep_the_sheets_date(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, start=date(2099, 4, 1), end=date(2100, 3, 31), flat=1000, construct={"allowed": {"SPOUSE"}, "max_children": 2, "max_members": 6},
                    dependant_midterm={"SPOUSE": {"allowed": True, "start_basis": "EVENT"}})
    add_member(code="1", rel="SELF", dob="1985-01-01")
    job = job_from(upload(client_for(), csv("1,Spouse,Ravi,1983-02-02,M,500000,,2020-05-05"), effective_date="2099-06-01"))
    assert StagedRow.objects.get(job_id=job.id).normalised_payload["effective_date"] == "2099-04-01"      # marriage predates the policy
    _, payload, _ = run(flow, {"KIDS": {"allowed": True, "start_basis": "UPLOAD"}}, f"1,Spouse,Ravi,1983-02-02,M,500000,,{ago(5)}")
    assert payload["effective_date"] == "2026-06-01"                                                        # no SPOUSE rule


def test_an_employee_joining_in_the_same_file_is_not_a_midterm_dependant(flow):
    flow.add_policy(BROKER_A, 100, EMP_A, start=START, end=END, flat=1000, construct={"allowed": {"SPOUSE"}, "max_children": 2, "max_members": 6},
                    dependant_midterm={"SPOUSE": {"allowed": False, "start_basis": "UPLOAD"}})
    job = job_from(upload(client_for(), csv("1,Self,Asha,1985-01-01,F,500000,,", "1,Spouse,Ravi,1983-02-02,M,500000,,"), effective_date="2026-06-01"))
    assert "MIDTERM_DEPENDANT_NOT_ALLOWED" not in set(ValidationLog.objects.filter(job_id=job.id).values_list("rule_code", flat=True))
    assert {r.normalised_payload["effective_date"] for r in StagedRow.objects.filter(job_id=job.id)} == {"2026-06-01"}


def test_a_bad_marriage_date_is_a_row_error(flow):
    _, _, found = run(flow, {"SPOUSE": {"allowed": True, "start_basis": "UPLOAD"}}, "1,Spouse,Ravi,1983-02-02,M,500000,,not-a-date")
    assert ("INVALID_DATE_OF_MARRIAGE", "ERROR") in found
