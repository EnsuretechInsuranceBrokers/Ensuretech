"""Legacy rater inputs on the add-sheet: they are mapped (not dropped), normalised, and a salary-multiple row needs no Suminsured."""
from datetime import date
from decimal import Decimal  # noqa: F401

from apps.intake.normalize import normalize_row
from apps.intake.schema import suggest_mapping
from apps.validation.rules import issues_scalar

TODAY = date(2030, 6, 15)
HEADERS = ["Employee Code", "Relationship with Employee", "Employee First Name", "Employee DOB", "Suminsured", "Effective Date", "Employee Annual Salary",
           "Employee Variables in Salary", "Employee Number of Time Salary", "Insured Member Number of Time Salary", "Employee State", "Employee City", "Employee Pin Code",
           "Employee Premium", "Employer Premium", "OPD Suminsured", "OPD Employee Premium", "OPD Employer Premium", "Flex Plan", "Self SI Selection", "Cover Type",
           "Employee No of Time Suminsured", "CTC", "Location", "Zone", "Endorsement Number", "Type of Correction"]


def test_every_rater_input_header_of_the_legacy_sheet_maps_to_its_own_field():
    fields = {s.header: s.field for s in suggest_mapping(HEADERS)}
    assert fields["Employee Annual Salary"] == "annual_salary" and fields["CTC"] == "ctc"          # CTC is not salary
    assert fields["Employee Number of Time Salary"] == "emp_number_of_time_salary"
    assert fields["Insured Member Number of Time Salary"] == "mem_number_of_time_salary"
    assert fields["Employee Premium"] == "employee_premium" and fields["Employer Premium"] == "employer_premium"
    assert fields["OPD Suminsured"] == "opd_sum_insured" and fields["OPD Employee Premium"] == "opd_employee_premium"
    assert fields["Employee State"] == "state" and fields["Employee Pin Code"] == "pincode" and fields["Flex Plan"] == "flex_plan"
    assert fields["Endorsement Number"] == "endorsement_number" and fields["Type of Correction"] == "type_of_correction"
    leaving = {x.header: x.field for x in suggest_mapping(["Employee Code", "Relationship with Employee", "Endorsement Number", "Employee Leaving Date"])}
    assert leaving["Employee Leaving Date"] == "effective_date"       # a REMOVE sheet has no Effective Date column: the leaving date is the date
    assert all(v for v in fields.values()), [h for h, v in fields.items() if not v]


def test_rater_inputs_are_normalised_and_carried():
    raw = {"employee_code": "1", "relation": "Self", "first_name": "A", "dob": "01-01-1990", "effective_date": "01-04-2030", "annual_salary": "₹ 5,82,922",
           "number_of_time_salary": "2", "employee_premium": "0", "employer_premium": "5,503.52", "state": " Maharashtra ", "pincode": "400001"}
    row, errors = normalize_row(raw, today=TODAY)
    assert not errors
    assert row["annual_salary"] == "582922.00" and row["number_of_time_salary"] == "2.00" and row["employer_premium"] == "5503.52"
    assert row["state"] == "Maharashtra" and row["pincode"] == "400001" and row["flex_plan"] is None


def test_a_salary_multiple_row_needs_no_sum_insured_but_any_other_row_does():
    base = {"employee_code": "1", "relation_code": "SELF", "first_name": "A", "dob": "1990-01-01", "effective_date": "2030-04-01", "_errors": []}
    derived = {**base, "annual_salary": "500000.00", "number_of_time_salary": "2.00"}
    assert not [i for i in issues_scalar(derived, "ADD", TODAY) if i.rule == "REQUIRED_FIELD"]
    for partial in ({**base, "annual_salary": "500000.00"}, {**base, "number_of_time_salary": "2.00"}, base):
        assert [i.field for i in issues_scalar(partial, "ADD", TODAY) if i.rule == "REQUIRED_FIELD"] == ["sum_insured"]


def test_bad_multiples_and_negative_premiums_are_errors():
    row = {"employee_code": "1", "relation_code": "SELF", "first_name": "A", "dob": "1990-01-01", "effective_date": "2030-04-01", "sum_insured": "1000.00",
           "number_of_time_salary": "0.00", "employee_premium": "-1.00", "_errors": []}
    assert {i.rule for i in issues_scalar(row, "ADD", TODAY)} == {"MULTIPLE_NOT_POSITIVE", "PREMIUM_NEGATIVE"}


# ── through the real upload + validation pipeline ─────────────────────────────────────────────────────────────
import pytest  # noqa: E402

from apps.intake.models import StagedRow  # noqa: E402
from apps.validation.models import ValidationLog  # noqa: E402

from .conftest import client_for, upload  # noqa: E402
from .test_computation_flow import job_from  # noqa: E402

LEGACY_HEADER = ("Employee Code,Relationship with Employee,Employee First Name,Insured Member First Name,Employee DOB,Insured Member DOB,Suminsured,Effective Date,"
                 "Employee Annual Salary,Employee Number of Time Salary,Employee State,Employee Premium,Employer Premium")


@pytest.mark.django_db(transaction=True)
def test_a_salary_multiple_sheet_with_no_suminsured_passes_validation_and_keeps_its_rater_inputs(world):
    text = "\n".join([LEGACY_HEADER,
                      "7001,Self,Asha,,01-01-1985,,,01-04-2030,600000,2,Maharashtra,0,5503.52",
                      "7001,Spouse,,Ravi,,02-02-1983,,01-04-2030,,,,0,5503.52",
                      "7002,Self,Meera,,01-01-1990,,,01-04-2030,,,Karnataka,0,100"]) + "\n"        # 7002 has no salary: Suminsured really is missing
    job = job_from(upload(client_for(), text, effective_date="2030-04-01"))
    errors = {(e.source_line, e.rule_code, e.field_name) for e in ValidationLog.objects.filter(job_id=job.id, severity="ERROR")}
    assert errors == {(4, "REQUIRED_FIELD", "sum_insured")}                 # only the row the rater could not compute
    rows = {(s.normalised_payload["employee_code"], s.normalised_payload["relation_code"]): s.normalised_payload for s in StagedRow.objects.filter(job_id=job.id)}
    me = rows[("7001", "SELF")]
    assert (me["annual_salary"], me["number_of_time_salary"], me["state"], me["employer_premium"]) == ("600000.00", "2.00", "Maharashtra", "5503.52")
