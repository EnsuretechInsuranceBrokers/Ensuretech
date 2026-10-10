"""Who pays what share of a member's premium (legacy premium_contribution_type + the per-relation *_employee_contribution settings).

PERCENT: the employee pays that % of the member's premium. FIXED: the employee pays that amount per member, never more than the premium.
The employer pays the rest. A policy with no contribution plan has no split (None), not "the employer pays": nobody has said who pays.
"""
from decimal import Decimal

CHILD_PREFIXES = ("SON", "DAUGHTER")
PARENTS = {"FATHER", "MOTHER", "STEP_FATHER", "STEP_MOTHER", "GRANDFATHER", "GRANDMOTHER"}
IN_LAWS = {"FATHER_IN_LAW", "MOTHER_IN_LAW"}
SPOUSES = {"SPOUSE", "HUSBAND", "WIFE", "DOMESTIC_PARTNER"}


def kind(relation_code: str) -> str:
    if relation_code == "SELF":
        return "SELF"
    if relation_code in SPOUSES:
        return "SPOUSE"
    if relation_code.startswith(CHILD_PREFIXES) or relation_code in ("ADOPTED_CHILD", "STEP_CHILD"):
        return "CHILD"
    if relation_code in PARENTS:
        return "PARENT"
    if relation_code in IN_LAWS:
        return "PARENT_IN_LAW"
    return "OTHERS"


def group_of(relation_code: str, family_relations: list[str]) -> str:
    """The contribution group of one member of a family in a given state. Parents are 'single' when one is covered, 'double' when both are
    (legacy falls back to the single value when no double value is set; see ContributionPlan.value)."""
    k = kind(relation_code)
    if k in ("PARENT", "PARENT_IN_LAW"):
        same = sum(1 for r in family_relations if kind(r) == k)
        return f"{k}_{'DOUBLE' if same > 1 else 'SINGLE'}"
    return k


class ContributionPlan:
    def __init__(self, type_: str, groups: dict[str, str] | None):
        self.type = type_ if type_ in ("PERCENT", "FIXED") else "NONE"
        self.groups = {k: Decimal(str(v)) for k, v in (groups or {}).items()}

    @property
    def enabled(self) -> bool:
        return self.type != "NONE"

    def value(self, group: str) -> Decimal:
        if group in self.groups:
            return self.groups[group]
        if group.endswith("_DOUBLE") and group.replace("_DOUBLE", "_SINGLE") in self.groups:
            return self.groups[group.replace("_DOUBLE", "_SINGLE")]          # legacy: no double value set -> use the single one
        return Decimal(0)                                                      # unset = the employee contributes nothing

    def employee_share(self, group: str, premium: Decimal) -> Decimal:
        """The part of one member's premium the employee pays."""
        if premium <= 0:
            return Decimal(0)
        v = self.value(group)
        if self.type == "PERCENT":
            return premium * min(max(v, Decimal(0)), Decimal(100)) / 100
        return min(premium, max(v, Decimal(0)))
