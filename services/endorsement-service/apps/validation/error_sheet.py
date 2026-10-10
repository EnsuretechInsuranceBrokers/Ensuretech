"""The error spreadsheet: what an HR user downloads after an upload to see what to fix.

Sheet "Rows to fix": one line per spreadsheet line that had at least one ERROR, with the row as it was read (so it can be corrected and uploaded again) and a
"Problems" column saying what is wrong with it. Sheet "All findings": every finding, errors and warnings, with its line, employee code, field, rule and value.
Cells that start with = + - or @ are written as text, so a hostile value in an uploaded file cannot become a formula when the sheet is opened.
"""
import io
import re

import openpyxl
from openpyxl.cell import WriteOnlyCell

from apps.intake.models import StagedRow

from .models import ValidationLog

# the order a person expects; any other field in the row follows
FIELD_ORDER = ["employee_code", "relation", "full_name", "first_name", "last_name", "dob", "gender", "sum_insured", "effective_date", "date_of_marriage", "date_of_joining", "pan", "email", "mobile"]
TITLE = {"employee_code": "Employee Code", "relation": "Relationship", "full_name": "Member Name", "dob": "DOB", "sum_insured": "Sum Insured", "effective_date": "Effective Date",
         "pan": "PAN", "date_of_marriage": "Date of Marriage", "date_of_joining": "Date of Joining"}
_FORMULA = re.compile(r"^[=+\-@\t\r]")


def _text(value) -> str:
    s = "" if value is None else str(value)
    return "'" + s if _FORMULA.match(s) else s


def _title(key: str) -> str:
    return TITLE.get(key, key.replace("_", " ").title())


def build(job) -> bytes:
    logs = list(ValidationLog.objects.filter(job_id=job.id).order_by("source_line", "id"))
    by_line: dict[int, list[ValidationLog]] = {}
    for r in logs:
        if r.severity == "ERROR":
            by_line.setdefault(r.source_line, []).append(r)
    lines = sorted(by_line)
    rows: dict[int, dict] = {}
    for i in range(0, len(lines), 1000):                              # keep the IN list small
        for s in StagedRow.objects.filter(job_id=job.id, source_line__in=lines[i:i + 1000]).values("source_line", "raw_payload"):
            rows[s["source_line"]] = s["raw_payload"] or {}
    keys: list[str] = [k for k in FIELD_ORDER if any(k in p for p in rows.values())]
    keys += sorted({k for p in rows.values() for k in p} - set(keys))

    wb = openpyxl.Workbook(write_only=True)
    ws = wb.create_sheet("Rows to fix")
    ws.append(["Line"] + [_title(k) for k in keys] + ["Problems", "Rules"])
    for n in lines:
        payload = rows.get(n, {})
        problems = "; ".join(f"{r.field_name + ': ' if r.field_name else ''}{r.message}" for r in by_line[n])
        ws.append([n] + [_text(payload.get(k)) for k in keys] + [_text(problems), ", ".join(sorted({r.rule_code for r in by_line[n]}))])
    allf = wb.create_sheet("All findings")
    allf.append(["Line", "Employee Code", "Field", "Rule", "Severity", "Message", "Value"])
    for r in logs:
        allf.append([r.source_line, _text(r.employee_code), _text(r.field_name), r.rule_code, r.severity, _text(r.message), _text(r.raw_value)])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def filename(job) -> str:
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", (job.source_filename or "upload").rsplit(".", 1)[0])[:60] or "upload"
    return f"{stem}_errors.xlsx"
