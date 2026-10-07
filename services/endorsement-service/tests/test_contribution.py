from decimal import Decimal

import pytest

from apps.computation.contribution import ContributionPlan, group_of, kind
from apps.jobs.models import PremiumLine

from .conftest import BROKER_A, EMP_A, add_member, client_for, csv_text, upload
from .test_computation_flow import job_from

D = Decimal
pytestmark = pytest.mark.django_db(transaction=True)
ALLOWED = {"SPOUSE", "SON", "FATHER", "MOTHER"}


def test_relations_map_to_contribution_groups_and_parents_are_single_or_double():
    assert [kind(r) for r in ("SELF", "WIFE", "SON_II", "DAUGHTER", "FATHER", "MOTHER_IN_LAW", "BROTHER")] == \
        ["SELF", "SPOUSE", "CHILD", "CHILD", "PARENT", "PARENT_IN_LAW", "OTHERS"]
    assert group_of("FATHER", ["SELF", "FATHER"]) == "PARENT_SINGLE"
    assert group_of("FATHER", ["SELF", "FATHER", "MOTHER"]) == "PARENT_DOUBLE"
    assert group_of("MOTHER_IN_LAW", ["SELF", "MOTHER_IN_LAW"]) == "PARENT_IN_LAW_SINGLE"


def test_percent_and_fixed_shares_and_their_edges():
    pct = ContributionPlan("PERCENT", {"SPOUSE": "50", "CHILD": "150", "SELF": "-5"})
    assert pct.employee_share("SPOUSE", D("1000")) == D("500")
    assert pct.employee_share("CHILD", D("1000")) == D("1000")          # a percentage is clamped to 0..100
    assert pct.employee_share("SELF", D("1000")) == 0
    fixed = ContributionPlan("FIXED", {"SELF": "300", "SPOUSE": "5000", "PARENT_SINGLE": "400"})
    assert fixed.employee_share("SELF", D("1000")) == D("300")
    assert fixed.employee_share("SPOUSE", D("1000")) == D("1000")       # never more than the premium
    assert fixed.employee_share("PARENT_DOUBLE", D("1000")) == D("400")  # legacy: no double value -> the single one
    assert fixed.employee_share("OTHERS", D("1000")) == 0                # unset group: the employee pays nothing
    assert fixed.employee_share("SELF", D("0")) == 0 and fixed.employee_share("SELF", D("-50")) == 0   # refund of nothing, no phantom share
    assert not ContributionPlan("NONE", {"SELF": "1"}).enabled and not ContributionPlan(None, None).enabled


def policy(flow, plan):
    flow.add_policy(BROKER_A, 100, EMP_A, pro_rata="NONE", flat=1000, contribution=plan,
                    construct={"allowed": ALLOWED, "max_children": 2, "max_members": 6})


FAMILY = csv_text("1,Self,Asha,1985-01-01,F,500000,,", "1,Spouse,Ravi,1983-02-02,M,500000,,", "1,Son,Kid,2015-01-01,M,500000,,")


def test_percent_split_of_an_added_family(flow):
    policy(flow, ("PERCENT", {"SELF": "0", "SPOUSE": "50", "CHILD": "50"}))
    job = job_from(upload(client_for(), FAMILY, effective_date="2030-04-01"))
    line = PremiumLine.objects.get(job_id=job.id)
    assert line.charged == D("3000.00") and line.employee_charged == D("1000.00") and line.employer_charged == D("2000.00")


def test_fixed_split_is_capped_at_each_members_premium(flow):
    policy(flow, ("FIXED", {"SELF": "300", "SPOUSE": "1200", "CHILD": "600"}))
    line = PremiumLine.objects.get(job_id=job_from(upload(client_for(), FAMILY, effective_date="2030-04-01")).id)
    assert (line.employee_charged, line.employer_charged) == (D("1900.00"), D("1100.00"))
    assert line.employee_charged + line.employer_charged == line.charged


def test_no_plan_means_no_split_not_employer_pays_all(flow):
    policy(flow, None)
    line = PremiumLine.objects.get(job_id=job_from(upload(client_for(), FAMILY, effective_date="2030-04-01")).id)
    assert line.employee_charged is None and line.employer_charged is None and line.charged == D("3000.00")


def test_adding_the_second_parent_reprices_the_first_parents_share(flow):
    policy(flow, ("FIXED", {"PARENT_SINGLE": "400", "PARENT_DOUBLE": "700"}))
    add_member(code="9", rel="SELF", premium="1000.00")
    add_member(code="9", rel="FATHER", dob="1955-01-01", premium="1000.00")
    job = job_from(upload(client_for(), csv_text("9,Mother,Mum,1957-01-01,F,500000,,"), effective_date="2030-04-01"))
    line = PremiumLine.objects.get(job_id=job.id)
    # before: father alone pays 400. after: father and mother are a double (700 each) = 1400. the endorsement moves the employee's share by 1000
    assert line.charged == D("1000.00") and line.employee_charged == D("1000.00") and line.employer_charged == D("0.00")
