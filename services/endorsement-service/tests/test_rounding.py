from decimal import Decimal

import pytest

from apps.computation.pro_rata import round_amount
from apps.jobs.models import PremiumLine

from .conftest import BROKER_A, EMP_A, client_for, csv_text, upload
from .test_computation_flow import job_from

D = Decimal
pytestmark = pytest.mark.django_db(transaction=True)


def test_round_amount_follows_the_legacy_round_to():
    assert round_amount(D("1165844"), "ROUND", 1000) == D("1166000.00")
    assert round_amount(D("1165500"), "ROUND", 1000) == D("1166000.00")        # half rounds up, like PHP round()
    assert round_amount(D("1165499.99"), "ROUND", 1000) == D("1165000.00")
    assert round_amount(D("1234.01"), "FLOOR", 10) == D("1230.00")
    assert round_amount(D("1234.01"), "CEIL", 10) == D("1240.00")
    assert round_amount(D("-1495.89"), "ROUND", 10) == D("-1500.00")           # a refund rounds away from zero on ROUND
    assert round_amount(D("-1495.89"), "FLOOR", 10) == D("-1500.00")           # FLOOR/CEIL are mathematical (toward -inf / +inf)
    assert round_amount(D("-1495.89"), "CEIL", 10) == D("-1490.00")
    assert round_amount(D("12.345"), "NONE", 10) == D("12.35") and round_amount(D("12.345"), "BOGUS", 10) == D("12.35")   # unknown kind keeps paise
    assert round_amount(D("12.5"), "ROUND", 0) == D("13.00")                    # a unit of 0 is treated as 1


FAMILY = csv_text("1,Self,Asha,1985-01-01,F,500000,,", "1,Spouse,Ravi,1983-02-02,M,500000,,", "1,Son,Kid,2015-01-01,M,500000,,")


def charged(flow, rounding, contribution=None):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="DAILY", flat=1000, rounding=rounding, contribution=contribution,
                    construct={"allowed": {"SPOUSE", "SON"}, "max_children": 2, "max_members": 6})
    return PremiumLine.objects.get(job_id=job_from(upload(client_for(), FAMILY, effective_date="2030-10-01")).id)


# 3 members x 1000 x 182/365 days = 1495.89
@pytest.mark.parametrize("rounding,expected", [(("NONE", 1), "1495.89"), (("ROUND", 1), "1496.00"), (("ROUND", 10), "1500.00"),
                                              (("FLOOR", 10), "1490.00"), (("CEIL", 10), "1500.00"), (("FLOOR", 100), "1400.00")])
def test_the_charged_amount_is_rounded_and_tax_follows_the_rounded_amount(flow, rounding, expected):
    line = charged(flow, rounding)
    assert line.charged == D(expected)
    assert line.tax == (D(expected) * D("18") / 100).quantize(D("0.01")) and line.total == line.charged + line.tax


def test_the_employee_employer_split_still_adds_up_after_rounding(flow):
    line = charged(flow, ("FLOOR", 10), ("PERCENT", {"SELF": "0", "SPOUSE": "50", "CHILD": "50"}))
    assert line.charged == D("1490.00")
    assert line.employee_charged + line.employer_charged == line.charged
    assert D(0) <= line.employee_charged <= line.charged
