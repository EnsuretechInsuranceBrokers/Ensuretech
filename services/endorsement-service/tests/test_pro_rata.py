from datetime import date
from decimal import Decimal

import pytest

from apps.computation.pro_rata import pro_rata_factor

START, END = date(2030, 4, 1), date(2031, 3, 31)           # a 365-day policy year


def f(kind, eff, **kw):
    return pro_rata_factor(kind, START, END, eff, **kw)


def test_none_and_inception_are_always_full_premium():
    assert f("NONE", date(2030, 10, 1)) == 1
    assert f("EXACT_DAYS", date(2030, 10, 1), inception=True) == 1
    assert f("FULL_MONTH", date(2030, 10, 1), inception=True) == 1


def test_exact_days_counts_both_ends():
    assert f("EXACT_DAYS", START) == 1                                  # whole year
    assert f("EXACT_DAYS", END) == (Decimal(1) / Decimal(365)).quantize(Decimal("0.000001"))   # last day only
    assert f("EXACT_DAYS", date(2030, 10, 1)) == (Decimal(182) / Decimal(365)).quantize(Decimal("0.000001"))
    assert f("EXACT_DAYS", date(2030, 4, 2)) == (Decimal(364) / Decimal(365)).quantize(Decimal("0.000001"))


def test_full_month_counts_the_effective_month_as_whole():
    assert f("FULL_MONTH", START) == 1
    assert f("FULL_MONTH", date(2030, 4, 30)) == 1                      # April is still counted: 12/12
    assert f("FULL_MONTH", date(2030, 5, 1)) == (Decimal(11) / Decimal(12)).quantize(Decimal("0.000001"))
    assert f("FULL_MONTH", date(2031, 3, 15)) == (Decimal(1) / Decimal(12)).quantize(Decimal("0.000001"))


def test_outside_the_policy_period():
    assert f("EXACT_DAYS", date(2030, 1, 1)) == 1                       # before start: full
    assert f("EXACT_DAYS", date(2031, 4, 1)) == 0                       # after end: nothing left
    assert f("FULL_MONTH", date(2031, 4, 1)) == 0


def test_leap_year_policy():
    assert pro_rata_factor("EXACT_DAYS", date(2031, 4, 1), date(2032, 3, 31), date(2031, 10, 1)) == \
        (Decimal(183) / Decimal(366)).quantize(Decimal("0.000001"))


def test_unknown_kind_is_an_error():
    with pytest.raises(ValueError):
        f("QUARTERLY", date(2030, 10, 1))


def test_factor_is_always_between_zero_and_one():
    d = START
    from datetime import timedelta
    while d <= END + timedelta(days=5):
        for kind in ("EXACT_DAYS", "FULL_MONTH"):
            assert 0 <= f(kind, d) <= 1
        d += timedelta(days=7)
