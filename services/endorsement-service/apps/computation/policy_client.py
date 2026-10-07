"""Client for policy-service. The engine never re-implements family rules or pricing; it asks."""
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Protocol

from django.conf import settings

from apps.core import service_auth
from django.utils.module_loading import import_string


class PolicyNotFound(Exception):
    """Policy does not exist for this broker (cross-tenant ids look exactly like missing ones)."""


class PolicyUnavailable(Exception):
    """Transient: policy-service is down, slow or overloaded. Safe to retry."""


class PolicyAccessError(Exception):
    """Our service credential was refused. Retrying will not help; an operator must fix the token."""


class PolicyRejected(Exception):
    """The request is understood but cannot be satisfied (e.g. the rate card cannot price an age)."""


@dataclass(frozen=True)
class PolicyInfo:
    policy_id: int
    employer_id: int
    policy_number: str
    policy_type: str
    status: str
    start_date: date
    end_date: date
    pro_rata_type: str
    is_inception: bool
    premium_type_code: str
    requires_family_construct: bool
    relation_prorata: dict = field(default_factory=dict)      # relation code -> DAILY | MONTHLY | NONE (already resolved by policy-service)


@dataclass(frozen=True)
class FamilyIssue:
    code: str
    message: str
    member_index: int          # -1 = whole family


@dataclass(frozen=True)
class Person:
    relation_code: str
    age: int


@dataclass(frozen=True)
class PremiumMember:
    ref: str
    relation_code: str
    age: int
    grade: str | None = None
    designation: str | None = None
    sum_insured: Decimal | None = None
    layers: tuple[str, ...] = ()


@dataclass(frozen=True)
class PremiumResult:
    by_ref: dict[str, Decimal]
    subtotal: Decimal
    tax_rate: Decimal
    tax: Decimal
    total: Decimal


class PolicyClient(Protocol):
    def get_policy(self, broker_id: int, policy_id: int) -> PolicyInfo: ...

    def validate_families(self, broker_id: int, policy_id: int,
                          families: dict[str, list[Person]]) -> dict[str, list[FamilyIssue]]: ...

    def calculate_premium(self, broker_id: int, policy_id: int, members: list[PremiumMember]) -> PremiumResult: ...

    def calculate_premiums(self, broker_id: int, policy_id: int,
                           families: "dict[str, list[PremiumMember]]") -> "dict[str, PremiumResult]": ...


def get_policy_client() -> PolicyClient:
    return import_string(settings.POLICY_CLIENT)()


class GrpcPolicyClient:
    """Plain gRPC client. SYSTEM service token + explicit broker_id; every call carries a deadline."""

    _channel = None

    def __init__(self):
        import grpc
        from .gen import policy_validation_pb2 as pb
        from .gen import policy_validation_pb2_grpc as pbg

        self._grpc, self._pb = grpc, pb
        if GrpcPolicyClient._channel is None:
            GrpcPolicyClient._channel = grpc.insecure_channel(settings.POLICY_GRPC_TARGET)   # TLS with deployment work
        self._stub = pbg.PolicyValidationStub(GrpcPolicyClient._channel)

    def _call(self, method, request):
        grpc = self._grpc
        try:
            auth = service_auth.bearer(settings.POLICY_GRPC_TOKEN)
        except service_auth.ServiceAuthError as e:
            raise PolicyUnavailable(str(e)) from e
        try:
            return method(request, timeout=settings.POLICY_GRPC_DEADLINE_SECONDS, metadata=(("authorization", auth),))
        except grpc.RpcError as e:
            code = e.code()
            if code == grpc.StatusCode.UNAUTHENTICATED:
                service_auth.provider.invalidate(auth[7:])          # next attempt fetches a fresh token
            if code == grpc.StatusCode.NOT_FOUND:
                raise PolicyNotFound(e.details()) from e
            if code in (grpc.StatusCode.UNAUTHENTICATED, grpc.StatusCode.PERMISSION_DENIED):
                raise PolicyAccessError(e.details()) from e
            if code in (grpc.StatusCode.INVALID_ARGUMENT, grpc.StatusCode.FAILED_PRECONDITION):
                raise PolicyRejected(e.details()) from e
            raise PolicyUnavailable(f"{code.name}: {e.details()}") from e      # UNAVAILABLE, DEADLINE_EXCEEDED, ...

    def get_policy(self, broker_id, policy_id):
        r = self._call(self._stub.GetPolicy, self._pb.PolicyRequest(policy_id=policy_id, broker_id=broker_id))
        return PolicyInfo(r.policy_id, r.employer_id, r.policy_number, r.policy_type, r.status,
                          date.fromisoformat(r.start_date), date.fromisoformat(r.end_date), r.pro_rata_type,
                          r.is_inception, r.premium_type_code, r.requires_family_construct,
                          {x.relation_code: x.pro_rata_type for x in r.relation_rules})

    def validate_families(self, broker_id, policy_id, families):
        pb = self._pb
        req = pb.FamilyValidationRequest(policy_id=policy_id, broker_id=broker_id, families=[
            pb.Family(family_ref=ref, people=[pb.Person(relation_code=p.relation_code, age=p.age) for p in people])
            for ref, people in families.items()])
        r = self._call(self._stub.ValidateFamilies, req)
        return {res.family_ref: [FamilyIssue(i.code, i.message, i.member_index) for i in res.issues]
                for res in r.results}

    def calculate_premium(self, broker_id, policy_id, members):
        pb = self._pb
        req = pb.PremiumRequest(policy_id=policy_id, broker_id=broker_id, members=[
            pb.MemberInput(member_ref=m.ref, relation_code=m.relation_code, age=m.age, grade=m.grade or "",
                           designation=m.designation or "",
                           sum_insured="" if m.sum_insured is None else str(m.sum_insured), layers=list(m.layers))
            for m in members])
        r = self._call(self._stub.CalculatePremium, req)
        return PremiumResult({m.member_ref: Decimal(m.premium) for m in r.members}, Decimal(r.subtotal),
                             Decimal(r.tax_rate), Decimal(r.tax), Decimal(r.total))

    MAX_FAMILIES_PER_CALL = 5000                       # the server rejects larger batches

    def calculate_premiums(self, broker_id, policy_id, families):
        """One RPC per batch: the server loads policy, rate card and relations once instead of once per family."""
        pb, out, refs = self._pb, {}, list(families)
        for start in range(0, len(refs), self.MAX_FAMILIES_PER_CALL):
            part = refs[start:start + self.MAX_FAMILIES_PER_CALL]
            req = pb.PremiumBatchRequest(policy_id=policy_id, broker_id=broker_id, families=[
                pb.FamilyPremiumRequest(family_ref=ref, members=[
                    pb.MemberInput(member_ref=m.ref, relation_code=m.relation_code, age=m.age, grade=m.grade or "",
                                   designation=m.designation or "",
                                   sum_insured="" if m.sum_insured is None else str(m.sum_insured),
                                   layers=list(m.layers)) for m in families[ref]]) for ref in part])
            for res in self._call(self._stub.CalculatePremiumBatch, req).results:
                if res.error:
                    raise PolicyRejected(f"employee {res.family_ref}: {res.error}")
                r = res.result
                out[res.family_ref] = PremiumResult({m.member_ref: Decimal(m.premium) for m in r.members},
                                                    Decimal(r.subtotal), Decimal(r.tax_rate), Decimal(r.tax), Decimal(r.total))
        return out


def calculate_premiums_one_by_one(client, broker_id: int, policy_id: int,
                                  families: dict[str, list[PremiumMember]]) -> dict[str, PremiumResult]:
    """Batch pricing on top of the single-family RPC. A rejection names the family so an operator can find it.
    (policy-service exposes CalculatePremium per family; a batch RPC is the obvious optimisation for 1M-row jobs.)"""
    out = {}
    for ref, members in families.items():
        try:
            out[ref] = client.calculate_premium(broker_id, policy_id, members)
        except PolicyRejected as e:
            raise PolicyRejected(f"employee {ref}: {e}") from e
    return out
