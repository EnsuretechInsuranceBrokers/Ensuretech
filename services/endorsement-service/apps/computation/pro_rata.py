"""Pro-rata factor for a mid-term endorsement. These are the rules this service applies; they are assumptions to be
confirmed against the legacy pro-rata logic (not available in this repo)."""
from datetime import date
from decimal import ROUND_HALF_UP, Decimal

SIX = Decimal("0.000001")


def pro_rata_factor(kind: str, start: date, end: date, effective: date, inception: bool = False) -> Decimal:
    """Fraction of the policy year still to run on ``effective``.

    * inception policies and NONE: always 1 (full premium).
    * EXACT_DAYS: remaining days / total days, both counted inclusively.
    * FULL_MONTH: the month of ``effective`` counts as a full month; remaining months / total months.
    Before the policy starts the factor is 1, after it ends 0.
    """
    if inception or kind == "NONE":
        return Decimal(1)
    if effective <= start:
        return Decimal(1)
    if effective > end:
        return Decimal(0)
    if kind == "EXACT_DAYS":
        remaining = (end - effective).days + 1
        total = (end - start).days + 1
        f = Decimal(remaining) / Decimal(total)
    elif kind == "FULL_MONTH":
        remaining = (end.year - effective.year) * 12 + end.month - effective.month + 1
        total = (end.year - start.year) * 12 + end.month - start.month + 1
        f = Decimal(remaining) / Decimal(total)
    else:
        raise ValueError(f"unknown pro-rata type {kind!r}")
    return min(max(f, Decimal(0)), Decimal(1)).quantize(SIX, rounding=ROUND_HALF_UP)


def money(d: Decimal) -> Decimal:
    return d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
