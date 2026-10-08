"""In-process stand-ins for policy-service and billing (CD). Class-level state so every `FakePolicyClient()` the
code under test creates sees the same world; call reset() in a fixture."""
from datetime import date
from decimal import Decimal

from apps.computation.cd import CdResult, CdUnavailable
from apps.computation.policy_client import (FamilyIssue, PolicyAccessError, PolicyInfo, PolicyNotFound, PolicyRejected,
                                            PolicyUnavailable, PremiumResult)

CHILD = {"SON", "SON_II", "SON_III", "SON_IV", "DAUGHTER", "DAUGHTER_II", "DAUGHTER_III"}


class FakePolicyClient:
    policies: dict[tuple[int, int], PolicyInfo] = {}
    constructs: dict[tuple[int, int], dict] = {}
    rates: dict[tuple[int, int], dict] = {}
    fail_next: list[Exception] = []          # popped one per remote call
    calls: list[str] = []

    @classmethod
    def reset(cls):
        cls.policies, cls.constructs, cls.rates, cls.fail_next, cls.calls = {}, {}, {}, [], []

    @classmethod
    def add_policy(cls, broker_id, policy_id, employer_id, *, status="APPROVED_AND_LIVE", policy_type="GMC",
                   pro_rata="NONE", start=date(2030, 4, 1), end=date(2031, 3, 31), inception=False, construct=None,
                   flat=None, relation_prorata=None, contribution=None, rounding=None, midterm="DATE_OF_JOINING", dependant_midterm=None):
        cls.policies[(broker_id, policy_id)] = PolicyInfo(
            policy_id, employer_id, f"POL-{policy_id}", policy_type, status, start, end, pro_rata, inception, "FLAT",
            policy_type in ("GMC", "OPD", "WELLNESS"), relation_prorata or {},
            *(contribution or ("NONE", {})), *(rounding or ("NONE", 1)), midterm, dependant_midterm or {})
        if construct is not None:
            cls.constructs[(broker_id, policy_id)] = construct
        if flat is not None:
            cls.rates[(broker_id, policy_id)] = {"flat": Decimal(str(flat)), "tax": Decimal("18")}

    def _maybe_fail(self, name):
        FakePolicyClient.calls.append(name)
        if FakePolicyClient.fail_next:
            raise FakePolicyClient.fail_next.pop(0)

    def get_policy(self, broker_id, policy_id):
        self._maybe_fail("get_policy")
        try:
            return self.policies[(broker_id, policy_id)]
        except KeyError:
            raise PolicyNotFound("no such policy") from None

    def validate_families(self, broker_id, policy_id, families):
        self._maybe_fail("validate_families")
        type(self).seen_families = {ref: list(people) for ref, people in families.items()}     # what policy-service would have received
        c = self.constructs.get((broker_id, policy_id))
        if c is None:
            raise PolicyNotFound("no family construct")
        out = {}
        for ref, people in families.items():
            issues, children, selves = [], 0, 0
            for i, p in enumerate(people):
                if p.relation_code == "SELF":
                    selves += 1
                elif p.relation_code not in c["allowed"]:
                    issues.append(FamilyIssue("RELATION_NOT_ALLOWED", f"member {i}: {p.relation_code} not covered", i))
                if p.relation_code in CHILD:
                    children += 1
                    if p.age > 25:
                        issues.append(FamilyIssue("DEPENDENT_OVER_MAX_AGE", f"member {i}: age {p.age}", i))
            if selves != 1:
                issues.append(FamilyIssue("SELF_COUNT", f"exactly one SELF required, got {selves}", -1))
            if len(people) > c.get("max_members", 6):
                issues.append(FamilyIssue("MAX_MEMBERS_EXCEEDED", f"{len(people)} members", -1))
            if children > c.get("max_children", 99):
                issues.append(FamilyIssue("MAX_CHILDREN_EXCEEDED", f"{children} children", -1))
            out[ref] = issues
        return out

    def calculate_premium(self, broker_id, policy_id, members):
        self._maybe_fail("calculate_premium")
        r = self.rates.get((broker_id, policy_id))
        if r is None:
            raise PolicyRejected("no active rate card")
        by_ref = {m.ref: r["flat"] for m in members}
        sub = sum(by_ref.values(), Decimal("0"))
        tax = (sub * r["tax"] / 100).quantize(Decimal("0.01"))
        return PremiumResult(by_ref, sub, r["tax"], tax, sub + tax)


    def calculate_premiums(self, broker_id, policy_id, families):
        from apps.computation.policy_client import calculate_premiums_one_by_one
        return calculate_premiums_one_by_one(self, broker_id, policy_id, families)


class InMemoryCd:
    """Deposit balance double. balance >= required -> SUFFICIENT; 0 < balance < required -> PARTIAL; else INSUFFICIENT."""
    balance = Decimal("0")
    down = False
    calls: list = []

    @classmethod
    def reset(cls, balance="1000000"):
        cls.balance, cls.down, cls.calls = Decimal(balance), False, []

    def check(self, broker_id, policy_id, required, job_id):
        InMemoryCd.calls.append((broker_id, policy_id, required))
        if InMemoryCd.down:
            raise CdUnavailable("billing down")
        b = InMemoryCd.balance
        status = "SUFFICIENT" if b >= required else ("PARTIAL_SUFFICIENT" if b > 0 else "INSUFFICIENT")
        return CdResult(status, b, required)


__all__ = ["FakePolicyClient", "PolicyUnavailable", "PolicyAccessError", "PolicyRejected", "PolicyNotFound"]
