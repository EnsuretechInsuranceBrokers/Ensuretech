"""The spreadsheet for large endorsements (more than the grid's limit of employees).

The legacy way: download a generic sheet, fill it in, upload it, wait, and learn from the validation report that column G was
dates in the wrong format. This sheet is built for the policy and the count, so most of those mistakes cannot be made inside Excel:
the headers are exactly the names our reader matches with full confidence, relation and gender are drop-downs, dates and money
refuse text, the effective date is filled in, and an Instructions tab says what each column wants. The upload then goes through the
chunked, parallel file path (parser -> chunks -> vectorised rules), which checks the whole file as it streams in.
"""
import io

from openpyxl import Workbook
from openpyxl.worksheet.datavalidation import DataValidation

from apps.validation.rules import REQUIRED

from .gui import LABELS, OPS
from .normalize import rules

HEADERS = ["employee_code", "relation", "first_name", "last_name", "dob", "gender", "pan", "mobile", "email", "grade", "designation", "annual_salary", "sum_insured", "effective_date"]
MAX_TEMPLATE_ROWS = 50_000
GENDERS = ["M", "F", "O"]
WIDTH = {"employee_code": 16, "relation": 18, "first_name": 18, "last_name": 18, "dob": 15, "gender": 10, "pan": 14, "mobile": 14, "email": 26, "grade": 12, "designation": 18,
         "annual_salary": 16, "sum_insured": 16, "effective_date": 16}
HELP = {
    "employee_code": "Your employee's code. Keep it the same on every row of one family. Required.",
    "relation": "Pick from the list. SELF for the employee; each dependant on their own row beneath. Required.",
    "first_name": "Required when adding people.",
    "last_name": "Optional.",
    "dob": "Date of birth, as a date (for example 17-May-1985). Required when adding people.",
    "gender": "M, F or O.",
    "pan": "Optional. Ten characters, like ABCDE1234F.",
    "mobile": "Optional. Ten digits starting 6-9.",
    "email": "Optional.",
    "grade": "Optional. Only if the policy's rate card depends on grade.",
    "designation": "Optional. Only if the policy's rate card depends on designation.",
    "annual_salary": "Optional. A number.",
    "sum_insured": "A number, no currency sign. Required when adding people unless the rater works it out from salary.",
    "effective_date": "The day cover starts. Filled in for you; change it for a row that differs.",
}


def build(op: str, count: int, *, effective_date: str | None, policy_number: str = "") -> bytes:
    """An .xlsx for `op` sized for `count` employees (one SELF row each; add dependant rows beneath their employee)."""
    if op not in OPS:
        raise ValueError("operation must be one of " + ", ".join(OPS))
    n = max(1, min(int(count), MAX_TEMPLATE_ROWS))
    wb = Workbook(write_only=True)
    lists = wb.create_sheet("Lists")
    codes = rules()["relation_codes"]
    for i in range(max(len(codes), len(GENDERS))):
        lists.append([codes[i] if i < len(codes) else None, GENDERS[i] if i < len(GENDERS) else None])
    lists.sheet_state = "hidden"

    ws = wb.create_sheet("Endorsement", 0)
    ws.freeze_panes = "C2"
    for idx, h in enumerate(HEADERS, start=1):
        ws.column_dimensions[_col(idx)].width = WIDTH[h]
    ws.append([LABELS.get(h, h) for h in HEADERS])
    col = {h: _col(i) for i, h in enumerate(HEADERS, start=1)}
    for i in range(n):
        row = [None] * len(HEADERS)
        row[HEADERS.index("effective_date")] = effective_date or None
        if op == "ADD":
            row[HEADERS.index("relation")] = "SELF"
        ws.append(row)
    last = n + 1 + 200                                       # validation also covers 200 spare rows for dependants

    def add(dv, field):
        dv.add(f"{col[field]}2:{col[field]}{last}")
        ws.data_validations.append(dv)

    rel = DataValidation(type="list", formula1=f"=Lists!$A$1:$A${len(codes)}", allow_blank=True, showErrorMessage=True, errorTitle="Relation", error="Pick a relation from the list.")
    add(rel, "relation")
    add(DataValidation(type="list", formula1=f"=Lists!$B$1:$B${len(GENDERS)}", allow_blank=True, showErrorMessage=True, errorTitle="Gender", error="M, F or O."), "gender")
    for f in ("dob", "effective_date"):
        add(DataValidation(type="date", operator="greaterThan", formula1="1900-01-01", allow_blank=True, showErrorMessage=True, errorTitle="Date", error="Enter a date."), f)
    for f in ("annual_salary", "sum_insured"):
        add(DataValidation(type="decimal", operator="greaterThanOrEqual", formula1="0", allow_blank=True, showErrorMessage=True, errorTitle="Amount", error="Enter a number, without a currency sign."), f)
    add(DataValidation(type="textLength", operator="equal", formula1="10", allow_blank=True, showErrorMessage=True, errorTitle="PAN", error="A PAN has ten characters."), "pan")

    guide = wb.create_sheet("Instructions", 1)
    guide.column_dimensions["A"].width = 22
    guide.column_dimensions["B"].width = 100
    guide.append(["Ensuretech endorsement sheet"])
    guide.append(["Policy", policy_number or ""])
    guide.append(["Operation", {"ADD": "Add people", "REMOVE": "Remove people", "UPDATE": "Change details"}[op]])
    guide.append(["Prepared for", f"{n} employee(s). One SELF row each is ready; put each dependant on their own row under the employee, with the same employee code."])
    guide.append([])
    guide.append(["Column", "What goes in it"])
    required = {f"{'relation' if f == 'relation_code' else f}" for f in REQUIRED[op]}
    for h in HEADERS:
        guide.append([LABELS.get(h, h) + (" *" if h in required else ""), HELP[h]])
    guide.append([])
    guide.append(["* required", "Rows that fail a rule are reported by line number after upload; the good rows can still go ahead."])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _col(i: int) -> str:
    s = ""
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s
