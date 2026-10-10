from datetime import date, timedelta
from decimal import Decimal

import pytest

from apps.computation.pro_rata import joiner_factor, months_ceil, months_floor, pro_rata_factor, removal_factor

START, END = date(2030, 4, 1), date(2031, 3, 31)           # a 365-day policy year
Q = Decimal("0.000001")


def q(n, d):
    return (Decimal(n) / Decimal(d)).quantize(Q)


def j(kind, eff, **kw):
    return joiner_factor(kind, START, END, eff, **kw)


def r(kind, leaving, cover_start=None, **kw):
    return removal_factor(kind, START, END, leaving, cover_start, **kw)


def test_months_between_dates():
    assert months_floor(date(2030, 4, 1), date(2031, 3, 31)) == 11 and months_ceil(date(2030, 4, 1), date(2031, 3, 31)) == 12
    assert months_ceil(date(2030, 6, 15), date(2031, 3, 31)) == 10 and months_ceil(date(2030, 6, 1), date(2031, 3, 31)) == 10
    assert months_ceil(date(2030, 4, 1), date(2030, 10, 1)) == 6 and months_floor(date(2030, 4, 1), date(2030, 10, 1)) == 6   # exact anniversary: no extra month
    assert months_ceil(date(2030, 4, 1), date(2030, 4, 2)) == 1 and months_ceil(date(2030, 4, 1), date(2030, 4, 1)) == 0
    assert months_ceil(date(2030, 1, 31), date(2030, 3, 1)) == 2                      # month-end start: Jan 31 + 1 month clamps to Feb 28


# ── joiners (calculatePremiumWithProRata) ────────────────────────────────────────────────────────────────────────
def test_none_and_inception_are_always_full_premium():
    assert j("NONE", date(2030, 10, 1)) == 1
    assert j("DAILY", date(2030, 10, 1), inception=True) == 1 and j("MONTHLY", date(2030, 10, 1), inception=True) == 1


def test_daily_counts_both_ends_and_a_start_on_or_before_policy_start_pays_in_full():
    assert j("DAILY", START) == 1 and j("DAILY", date(2029, 1, 1)) == 1
    assert j("DAILY", END) == q(1, 365)
    assert j("DAILY", date(2030, 10, 1)) == q(182, 365) and j("DAILY", date(2030, 4, 2)) == q(364, 365)
    assert j("DAILY", date(2031, 4, 1)) == 0                                                        # after the policy ends


def test_monthly_uses_ceil_of_fractional_months_for_both_numerator_and_denominator():
    assert j("MONTHLY", START) == 1
    assert j("MONTHLY", date(2030, 4, 30)) == q(12, 12)                                              # April is still a month: 12/12
    assert j("MONTHLY", date(2030, 5, 1)) == q(11, 12) and j("MONTHLY", date(2030, 6, 15)) == q(10, 12)
    assert j("MONTHLY", date(2031, 3, 15)) == q(1, 12) and j("MONTHLY", date(2031, 4, 1)) == 0


def test_leap_year_policy():
    assert joiner_factor("DAILY", date(2031, 4, 1), date(2032, 3, 31), date(2031, 10, 1)) == q(183, 366)


def test_unsupported_kinds_are_errors_not_silent_full_premium():
    for kind in ("QUARTERLY", "HALF_YEARLY", "EXACT_DAYS", "FULL_MONTH"):
        with pytest.raises(ValueError):
            j(kind, date(2030, 10, 1))
        with pytest.raises(ValueError):
            r(kind, date(2030, 10, 1))


# ── removals (getAdjustedPremium) ─────────────────────────────────────────────────────────────────────────────────
def test_daily_refund_keeps_the_leaving_day_covered():
    assert r("DAILY", date(2030, 10, 1)) == q(181, 365)                                              # Oct 2 .. Mar 31
    assert j("DAILY", date(2030, 10, 1)) - r("DAILY", date(2030, 10, 1)) == q(1, 365)               # joined and left the same day: one day paid for
    assert r("DAILY", END) == 0 and r("DAILY", date(2031, 4, 1)) == 0                                # leaving on the last day / after it: nothing to refund
    assert r("DAILY", date(2030, 1, 1)) == 1                                                         # left before the policy began: everything back


def test_daily_refund_for_someone_who_joined_mid_term_is_only_what_they_paid():
    assert r("DAILY", date(2030, 12, 31), date(2030, 10, 1)) == q(90, 365)
    assert r("DAILY", date(2030, 9, 1), date(2030, 10, 1)) == q(182, 365)                            # left before cover began: refund the whole joiner premium


def test_same_day_removal_variant_refunds_the_day_that_was_never_covered():
    assert r("DAILY", date(2030, 10, 1), date(2030, 10, 1)) == q(181, 365)
    assert r("DAILY", date(2030, 10, 1), date(2030, 10, 1), includes_leaving_day=False) == q(182, 365)
    assert r("DAILY", date(2030, 11, 1), date(2030, 10, 1), includes_leaving_day=False) == r("DAILY", date(2030, 11, 1), date(2030, 10, 1))


def test_monthly_refund():
    assert r("MONTHLY", date(2030, 9, 30)) == q(6, 12)                                               # April..September covered
    assert r("MONTHLY", date(2030, 10, 1)) == q(6, 12)                                               # legacy quirk: Oct 1 is an exact 6-month anniversary
    assert r("MONTHLY", date(2030, 10, 2)) == q(5, 12)
    assert r("MONTHLY", END) == 0 and r("MONTHLY", date(2030, 1, 1)) == 1
    assert r("MONTHLY", date(2030, 12, 31), date(2030, 10, 1)) == q(3, 12)


def test_removing_when_prorata_is_disabled_follows_the_configured_fallback():
    assert r("NONE", date(2030, 10, 1)) == q(181, 365)                                               # legacy: DAILY
    assert r("NONE", date(2030, 10, 1), when_disabled="FULL") == 1
    assert r("NONE", date(2030, 10, 1), when_disabled="NONE") == 0


def test_old_name_still_works_for_callers_that_only_add():
    assert pro_rata_factor("DAILY", START, END, date(2030, 10, 1)) == q(182, 365)


def test_factors_stay_between_zero_and_one_and_never_increase_as_the_start_moves_later():
    d, last = START - timedelta(days=3), Decimal(1)
    while d <= END + timedelta(days=5):
        for kind in ("DAILY", "MONTHLY"):
            assert 0 <= j(kind, d) <= 1 and 0 <= r(kind, d) <= 1
        f = j("DAILY", d)
        assert f <= last
        last, d = f, d + timedelta(days=1)
