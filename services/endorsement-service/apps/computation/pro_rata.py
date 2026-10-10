"""Pro-rata factors, ported from the legacy premium helpers (`calculatePremiumWithProRata`, `getAdjustedPremium`).

Kinds: DAILY and MONTHLY (QUARTERLY / HALF_YEARLY were never implemented in the legacy system). NONE means pro-rata is not allowed.
A factor is the fraction of the ANNUAL premium that applies; money is rounded once, by the caller.

* Joiner / increase from date E (`joiner_factor`): covered / total, where DAILY counts days from E to the policy end and over the whole
  policy, both inclusive; MONTHLY uses ceil(fractional months) for both, like Carbon's floatDiffInMonths. A start on or before the policy
  start pays in full.
* Removal on leaving date L (`removal_factor`): the part of the year after the cover ended is refunded. The leaving day itself is covered
  (the legacy default; TATA AIG's variant only differs for a removal on the day cover started - `includes_leaving_day=False`).
"""
import calendar
from datetime import date
from decimal import ROUND_CEILING, ROUND_FLOOR, ROUND_HALF_UP, Decimal

SIX = Decimal("0.000001")
KINDS = ("DAILY", "MONTHLY", "NONE")


def money(d: Decimal) -> Decimal:
    return d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def round_amount(d: Decimal, kind: str = "NONE", unit: int = 1) -> Decimal:
    """Legacy roundTo: FLOOR / CEIL / ROUND (half away from zero, like PHP round) to a multiple of ``unit``; NONE keeps paise."""
    if kind not in ("FLOOR", "CEIL", "ROUND"):
        return money(d)
    q = d / Decimal(max(int(unit or 1), 1))
    n = q.to_integral_value(rounding={"FLOOR": ROUND_FLOOR, "CEIL": ROUND_CEILING, "ROUND": ROUND_HALF_UP}[kind])
    return money(n * Decimal(max(int(unit or 1), 1)))


def _add_months(d: date, n: int) -> date:
    y, m = divmod(d.year * 12 + d.month - 1 + n, 12)
    return date(y, m + 1, min(d.day, calendar.monthrange(y, m + 1)[1]))


def months_floor(a: date, b: date) -> int:
    """Whole months from a to b (0 when b <= a)."""
    if b <= a:
        return 0
    m = (b.year - a.year) * 12 + b.month - a.month
    return m if _add_months(a, m) <= b else m - 1


def months_ceil(a: date, b: date) -> int:
    """ceil of the fractional month difference: whole months, plus one when any days are left over."""
    m = months_floor(a, b)
    return m + (1 if _add_months(a, m) < b else 0) if b > a else 0


def _q(n: int, d: int) -> Decimal:
    if d <= 0:
        return Decimal(0)
    return min(max(Decimal(n) / Decimal(d), Decimal(0)), Decimal(1)).quantize(SIX, rounding=ROUND_HALF_UP)


def _check(kind: str):
    if kind not in KINDS:
        raise ValueError(f"unknown pro-rata type {kind!r}")


def joiner_factor(kind: str, start: date, end: date, member_start: date, inception: bool = False) -> Decimal:
    _check(kind)
    if inception or kind == "NONE" or member_start <= start:
        return Decimal(1)
    if member_start > end:
        return Decimal(0)
    if kind == "DAILY":
        return _q((end - member_start).days + 1, (end - start).days + 1)
    return _q(months_ceil(member_start, end), months_ceil(start, end))


def removal_factor(kind: str, start: date, end: date, leaving: date, cover_start: date | None = None, *,
                   includes_leaving_day: bool = True, when_disabled: str = "DAILY") -> Decimal:
    """Fraction of the annual premium refunded for a member whose cover started on `cover_start` (default: the policy start) and ended on `leaving`."""
    _check(kind)
    cs = max(cover_start or start, start)
    if kind == "NONE":                                       # legacy still refunds daily when pro-rata premium is not allowed (see README)
        if when_disabled == "FULL":
            return Decimal(1)
        if when_disabled == "NONE":
            return Decimal(0)
        kind = "DAILY"
    if leaving > end:
        return Decimal(0)
    total_days = (end - start).days + 1
    if kind == "DAILY":
        last_covered = leaving
        if not includes_leaving_day and leaving == cs:
            last_covered = date.fromordinal(cs.toordinal() - 1)      # same-day removal: no day was covered
        refund_days = (end - max(last_covered, date.fromordinal(cs.toordinal() - 1))).days
        return _q(min(refund_days, (end - cs).days + 1), total_days)
    total = months_ceil(start, end)
    span = months_ceil(cs, end)
    covered = months_ceil(start, leaving) - months_floor(start, cs)
    refund = span if covered <= 0 else max(0, span - covered)
    return _q(refund, total)


def pro_rata_factor(kind: str, start: date, end: date, effective: date, inception: bool = False) -> Decimal:
    """Joiner factor (kept under its old name for callers that only add or change cover)."""
    return joiner_factor(kind, start, end, effective, inception)
