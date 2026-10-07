"""The GUI (scalar) and bulk (pandas) validators must agree on every row, or the same data would pass in one path and
fail in the other. Fuzzed over thousands of rows with edge values."""
import random
from collections import Counter
from datetime import date

import pytest

from apps.validation.rules import COLUMNS, UPDATABLE, issues_scalar, issues_vectorized, to_frame

TODAY = date(2030, 6, 15)
POOLS = {
    "employee_code": ["E1", "E2", None, ""],
    "relation_code": ["SELF", "SPOUSE", "SON", None, ""],
    "first_name": ["Asha", None, "", "  "],
    "last_name": ["Rao", None],
    "dob": ["1990-01-01", "2030-12-31", "2030-06-15", "1850-01-01", "2000-02-29", "2029-01-01", None, ""],
    "gender": ["M", "F", None],
    "pan": ["ABCDE1234F", "abcde1234f", "ABCDE12345", "", None, "AB"],
    "mobile": ["9876543210", "1234567890", "98765", None, ""],
    "email": ["a@b.co", "bad", "a@b", "", None],
    "grade": ["A", None],
    "designation": ["MGR", None],
    "annual_salary": ["100000.00", "-1.00", "0.00", None],
    "sum_insured": ["500000.00", "0.00", "-5.00", None, ""],
    "effective_date": ["2030-04-01", "1990-01-01", "1850-01-01", "2030-06-15", None, ""],
}
PARSE_ERRORS = [
    {"field": "dob", "rule": "INVALID_DOB", "message": "unreadable date 'zz'", "value": "zz"},
    {"field": "relation", "rule": "INVALID_RELATION", "message": "unrecognised relation", "value": "Cousin"},
    {"field": "sum_insured", "rule": "INVALID_SUM_INSURED", "message": "not a number", "value": "abc"},
    {"field": "effective_date", "rule": "INVALID_EFFECTIVE_DATE", "message": "unreadable", "value": "??"},
    {"field": "employee_code", "rule": "EMPLOYEE_ROWS_NOT_CONTIGUOUS", "message": "x", "value": "E1"},
]


def random_row(rng):
    row = {c: rng.choice(POOLS[c]) for c in COLUMNS}
    errs = rng.sample(PARSE_ERRORS, rng.choice([0, 0, 0, 1, 2]))
    for e in errs:                                    # a parse error means the normaliser left the cell empty
        col = {"relation": "relation_code"}.get(e["field"], e["field"])
        if col in row:
            row[col] = None
    if rng.random() < 0.15:                           # an UPDATE that changes nothing
        for c in UPDATABLE:
            row[c] = rng.choice([None, ""])
    row["_errors"] = errs
    return row


def signature(issues):
    by_row = {}
    for i in issues:
        by_row.setdefault(i.row_index, Counter())[(i.field, i.rule, i.severity)] += 1
    return by_row


@pytest.mark.parametrize("op", ["ADD", "REMOVE", "UPDATE"])
@pytest.mark.parametrize("seed", [1, 2, 3])
def test_scalar_and_vectorized_agree(op, seed):
    rng = random.Random(seed)
    rows = [random_row(rng) for _ in range(1500)]
    vec = signature(issues_vectorized(to_frame(rows), op, TODAY))
    sca = signature([i for n, r in enumerate(rows) for i in issues_scalar(r, op, TODAY, n)])
    diffs = [(n, rows[n], dict(sca.get(n, {})), dict(vec.get(n, {}))) for n in range(len(rows))
             if sca.get(n, Counter()) != vec.get(n, Counter())]
    assert not diffs, f"{len(diffs)} rows disagree; first: {diffs[0]}"


def test_fuzz_exercises_every_rule():
    rng = random.Random(7)
    rows = [random_row(rng) for _ in range(3000)]
    seen = set()
    for op in ("ADD", "REMOVE", "UPDATE"):
        seen |= {i.rule for i in issues_vectorized(to_frame(rows), op, TODAY)}
    expected = {"REQUIRED_FIELD", "NOTHING_TO_UPDATE", "DOB_IN_FUTURE", "DOB_AFTER_EFFECTIVE_DATE", "AGE_OUT_OF_RANGE",
                "INVALID_PAN", "INVALID_EMAIL", "INVALID_MOBILE", "SUM_INSURED_NOT_POSITIVE", "SALARY_NEGATIVE",
                "INVALID_DOB", "INVALID_RELATION", "INVALID_SUM_INSURED", "EMPLOYEE_ROWS_NOT_CONTIGUOUS"}
    assert expected <= seen, f"fuzz never produced: {expected - seen}"


def test_scalar_validator_is_fast_enough_for_interactive_use():
    import time
    row = {c: POOLS[c][0] for c in COLUMNS} | {"_errors": []}
    t0 = time.perf_counter()
    for _ in range(2000):
        issues_scalar(row, "ADD", TODAY)
    per_call_ms = (time.perf_counter() - t0) / 2000 * 1000
    assert per_call_ms < 5, f"{per_call_ms:.2f} ms per row"      # budget is <50 ms end to end
