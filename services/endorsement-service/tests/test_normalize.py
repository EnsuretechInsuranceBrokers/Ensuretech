from datetime import date

import pytest

from apps.intake.normalize import (normalize_amount, normalize_date, normalize_employee_code, normalize_gender,
                                   normalize_mobile, normalize_relation, normalize_row, split_full_name)

TODAY = date(2030, 1, 1)


@pytest.mark.parametrize("raw,source,expected", [
    ("Self", "", "SELF"), ("SELF", "", "SELF"), ("Employee", "", "SELF"), ("EMP", "FHPL", "SELF"),
    ("Spouse", "", "SPOUSE"), ("Wife", "", "WIFE"), ("SON-II", "", "SON_II"), ("Son 2", "", "SON_II"),
    ("son iii", "", "SON_III"), ("Daughter-IV", "", "DAUGHTER_IV"), ("Son", "", "SON"), ("Son 1", "", "SON"),
    ("F-IN-LAW", "", "FATHER_IN_LAW"), ("Mother In Law", "MEDIASSIST", "MOTHER_IN_LAW"),
    ("M", "FHPL", "MOTHER"), ("S", "FHPL", "SON"), ("D", "FHPL", "DAUGHTER"), ("Grand Father", "", "GRANDFATHER"),
])
def test_relation_normalisation(raw, source, expected):
    assert normalize_relation(raw, source) == (expected, None)


def test_unknown_relation_is_an_error_not_an_exception():
    code, err = normalize_relation("Cousin")
    assert code is None and "Cousin" in err
    assert normalize_relation("") == (None, None)
    # a one-letter FHPL label must NOT be accepted for a different source
    assert normalize_relation("M", "CARE")[0] is None


@pytest.mark.parametrize("raw,iso", [
    ("1990-05-17", "1990-05-17"), ("17/05/1990", "1990-05-17"), ("17-05-1990", "1990-05-17"),
    ("17.05.1990", "1990-05-17"), ("17-May-1990", "1990-05-17"), ("17 May 1990", "1990-05-17"),
    ("17/05/85", "1985-05-17"), ("17/05/05", "2005-05-17"), ("32000", "1987-08-11"), ("32000.0", "1987-08-11"),
])
def test_date_formats(raw, iso):
    assert normalize_date(raw, today=TODAY) == (iso, None)


@pytest.mark.parametrize("raw", ["31/02/1990", "garbage", "13/13/2000", "1990"])
def test_bad_dates_are_errors(raw):
    value, err = normalize_date(raw, today=TODAY)
    assert value is None and err


def test_dob_format_hint_resolves_month_first_dates():
    assert normalize_date("05/17/1990", "%m/%d/%Y", TODAY) == ("1990-05-17", None)
    assert normalize_date("03/04/1990", "%m/%d/%Y", TODAY) == ("1990-03-04", None)      # hint wins over DD/MM
    assert normalize_date("03/04/1990", None, TODAY) == ("1990-04-03", None)            # default is DD/MM


@pytest.mark.parametrize("raw,expected", [("₹ 5,00,000.50", "500000.50"), ("Rs. 1000", "1000.00"), ("INR 2,500", "2500.00"),
                                          ("750000", "750000.00"), ("-5", "-5.00")])
def test_amounts(raw, expected):
    assert normalize_amount(raw) == (expected, None)


@pytest.mark.parametrize("raw", ["abc", "1e999999", "NaN", "Infinity", "1e13", "99999999999999"])
def test_bad_amounts(raw):
    assert normalize_amount(raw)[0] is None


def test_small_cells():
    assert normalize_employee_code(" 'E0012 ") == "E0012"
    assert normalize_employee_code("1234.0") == "1234"
    assert normalize_employee_code("E 12 3") == "E123"
    assert normalize_employee_code("") is None
    assert normalize_gender("female") == ("F", None) and normalize_gender("x")[0] is None
    assert normalize_mobile("+91 98765 43210") == "9876543210" and normalize_mobile("09876543210") == "9876543210"
    assert split_full_name("Asha Rao Iyer") == ("Asha Rao", "Iyer") and split_full_name("Madonna") == ("Madonna", None)


def test_whole_row_collects_parse_errors_without_raising():
    row, errs = normalize_row({"employee_code": "1001.0", "relation": "Cousin", "full_name": "Asha Rao", "dob": "31/02/1990",
                               "sum_insured": "abc", "gender": "female", "effective_date": ""},
                              default_effective_date="2030-04-01")
    assert row["employee_code"] == "1001" and row["first_name"] == "Asha" and row["last_name"] == "Rao"
    assert row["effective_date"] == "2030-04-01" and row["gender"] == "F"
    assert {e["rule"] for e in errs} == {"INVALID_RELATION", "INVALID_DOB", "INVALID_SUM_INSURED"}
