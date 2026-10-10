"""The result spreadsheet, in the legacy format: what an HR user downloads after an upload (legacy name: AddMemberError.xlsx).

One sheet named "Worksheet", the legacy column layout for the operation (see legacy_layouts.py), then two columns: Status and Reason. There is one row for EVERY row of the
uploaded file, in the order it was uploaded, not just the failures: "Member added successfully." with an empty Reason for a row that was endorsed, "Failed to add." with the
reason(s) for a row that failed validation. A row that passed but has not been endorsed (the upload is still running, held, or stopped for another reason) says "Not yet added."
with the state of the upload as its Reason, so nobody reads a pending row as done. Dates are dd-mm-yyyy and genders are words, like the legacy files.
Cells that start with = + - or @ are written as text, so a hostile value in an uploaded file cannot become a formula when the sheet is opened.
"""
import datetime as dt
import io
import re

import openpyxl

from apps.intake.models import StagedRow
from apps.jobs.models import AppliedRow

from . import legacy_layouts
from .models import ValidationLog

LAYOUT = {"ADD": legacy_layouts.ADD, "REMOVE": legacy_layouts.REMOVE, "UPDATE": legacy_layouts.UPDATE}
FILENAME = {"ADD": "AddMemberError.xlsx", "REMOVE": "RemoveMemberError.xlsx", "UPDATE": "UpdateMemberError.xlsx"}
VERB = {"ADD": "add", "REMOVE": "remove", "UPDATE": "update"}
DONE = {"ADD": "Member added successfully.", "REMOVE": "Member removed successfully.", "UPDATE": "Member updated successfully."}
_FORMULA = re.compile(r"^[=+\-@\t\r]")
_NUMBER = re.compile(r"^-?\d{1,15}(\.\d+)?$")
GENDER = {"F": "Female", "FEMALE": "Female", "M": "Male", "MALE": "Male", "O": "Other", "OTHER": "Other", "T": "Transgender"}
# normalised field -> legacy header, for the columns that do not depend on whether the row is the employee or a dependant
COMMON = {"employee_code": "Employee Code", "sum_insured": "Suminsured", "date_of_marriage": "Date of Marriage", "date_of_joining": "Employee Date of Joining", "grade": "Employee Grade",
          "designation": "Employee Designation", "annual_salary": "Employee Annual Salary", "salary_variables": "Employee Variables in Salary", "number_of_time_salary": "Employee Number of Time Salary",
          "state": "Employee State", "city": "Employee City", "pincode": "Employee Pin Code", "ctc": "CTC", "location": "Location", "zone": "Zone", "flex_plan": "Flex Plan",
          "self_si_selection": "Self SI Selection", "cover_type": "Cover Type", "member_existing_claim": "Member Existing Claim", "no_of_time_suminsured": "Employee No of Time Suminsured",
          "employee_premium": "Employee Premium", "employer_premium": "Employer Premium", "opd_sum_insured": "OPD Suminsured", "opd_employee_premium": "OPD Employee Premium",
          "opd_employer_premium": "OPD Employer Premium", "endorsement_number": "Endrosement Number", "type_of_correction": "Type Of Correction"}
PERSON = ("first_name", "last_name", "gender", "dob", "email", "mobile")
EMPLOYEE_LABEL = {"first_name": "Employee First Name", "last_name": "Employee Last Name", "gender": "Employee Gender", "dob": "Employee DOB", "email": "Employee Email", "mobile": "Employee Mobile Number"}
MEMBER_LABEL = {"first_name": "Insured Member First Name", "last_name": "Insured Member Last Name", "gender": "Insured Member Gender", "dob": "Insured Member DOB", "email": "Insured Member Email",
                "mobile": "Insured Member Mobile Number"}
DATES = {"dob", "date_of_marriage", "date_of_joining"}


def _text(value) -> str:
    s = "" if value is None else str(value)
    return "'" + s if _FORMULA.match(s) else s


NUMERIC = {"Suminsured", "Employee Mobile Number", "Insured Member Mobile Number", "Employee Annual Salary", "Employee Variables in Salary", "Employee Number of Time Salary",
           "Employee Premium", "Employer Premium", "OPD Suminsured", "OPD Employer Premium", "OPD Employee Premium", "CTC", "Employee No of Time Suminsured"}


def _cell(label, value):
    """Amounts and mobile numbers stay numbers, as in the legacy files; everything else (names, codes, pin codes) is text; empty is empty."""
    if value is None or value == "":
        return None
    if label in NUMERIC:
        s = str(value).strip()
        if _NUMBER.match(s) and (not s.startswith("0") or s == "0" or "." in s[:2]):
            return int(float(s)) if float(s).is_integer() else float(s)
    return _text(value)


def _date(v) -> str:
    if not v:
        return ""
    try:
        return dt.date.fromisoformat(str(v)[:10]).strftime("%d-%m-%Y")
    except ValueError:
        return str(v)


def _split(name: str) -> tuple[str, str]:
    parts = (name or "").split()
    return (" ".join(parts[:-1]), parts[-1]) if len(parts) > 1 else (name or "", "")


def _relation(raw: dict, norm: dict) -> str:
    r = (raw.get("relation") or "").strip()
    return r if r else str(norm.get("relation_code") or "").replace("_", " ").title()


def _row_values(op: str, job, raw: dict, norm: dict) -> dict[str, object]:
    values: dict[str, object] = {}
    first, last = norm.get("first_name"), norm.get("last_name")
    if not first and raw.get("full_name"):
        first, last = _split(raw["full_name"])
    person = {"first_name": first, "last_name": last, "gender": GENDER.get(str(norm.get("gender") or raw.get("gender") or "").upper(), norm.get("gender") or raw.get("gender")),
              "dob": norm.get("dob") or raw.get("dob"), "email": norm.get("email"), "mobile": norm.get("mobile")}
    is_self = str(norm.get("relation_code") or _relation(raw, norm)).upper() == "SELF"
    labels = EMPLOYEE_LABEL if is_self else MEMBER_LABEL
    for k in PERSON:
        v = person[k]
        values[labels[k]] = _date(v) if k == "dob" and re.match(r"^\d{4}-\d\d-\d\d", str(v or "")) else v
    for k, label in COMMON.items():
        v = norm.get(k) if norm.get(k) not in (None, "") else raw.get(k)
        values[label] = _date(v) if k in DATES and re.match(r"^\d{4}-\d\d-\d\d", str(v or "")) else v
    values["Employee Code"] = norm.get("employee_code") or raw.get("employee_code")
    values["Relationship with Employee"] = _relation(raw, norm)
    values["Inception/Endorsement Date"] = _date(job.effective_date)
    if raw.get("effective_date"):
        values["Effective Date"] = _date(norm.get("effective_date") or raw["effective_date"])
    return values


def build(job) -> bytes:
    op = job.op_type if job.op_type in LAYOUT else "ADD"
    layout = LAYOUT[op]
    problems: dict[int, list[str]] = {}
    for r in ValidationLog.objects.filter(job_id=job.id, severity="ERROR").order_by("source_line", "id").values("source_line", "field_name", "message"):
        problems.setdefault(r["source_line"], []).append(f"{r['field_name']}: {r['message']}" if r["field_name"] else r["message"])
    applied = set(AppliedRow.objects.filter(job_id=job.id).values_list("chunk_index", "row_index"))
    waiting = f"Upload is {job.status.replace('_', ' ').lower()}."
    wb = openpyxl.Workbook(write_only=True)
    ws = wb.create_sheet("Worksheet")
    ws.append(layout + ["Status", "Reason"])
    for s in StagedRow.objects.filter(job_id=job.id).order_by("row_index").iterator(chunk_size=2000):
        raw, norm = s.raw_payload or {}, s.normalised_payload or {}
        values = _row_values(op, job, raw, norm)
        if s.source_line in problems:
            status, reason = f"Failed to {VERB[op]}.", "; ".join(problems[s.source_line])
        elif (s.chunk_index, s.row_index) in applied:
            status, reason = DONE[op], None
        else:
            status, reason = f"Not yet {VERB[op]}ed." if op != "ADD" else "Not yet added.", waiting
        ws.append([_cell(h, values.get(h)) for h in layout] + [status, _text(reason) if reason else None])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def filename(job) -> str:
    return FILENAME.get(job.op_type, FILENAME["ADD"])
