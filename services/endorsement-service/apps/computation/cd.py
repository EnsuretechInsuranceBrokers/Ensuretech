"""Contingency Deposit (CD) balance check against billing-service.

The check RESERVES the money for the job when the deposit covers it (one reservation per job id, re-evaluated on every
call), so two jobs cannot both pass on the same funds. billing-service later commits the reservation when it sees the
job's EndorsementApplied event, or releases it when the job is cancelled. UnavailableCdClient remains for environments
without billing: a job that reaches the CD step then parks visibly at CD_CHECK_FAILED instead of pretending."""
from dataclasses import dataclass
from decimal import Decimal
from typing import Protocol

from django.conf import settings
from django.utils.module_loading import import_string


class CdUnavailable(Exception):
    """Transient or not-yet-integrated: the balance could not be determined."""


@dataclass(frozen=True)
class CdResult:
    status: str                  # SUFFICIENT | PARTIAL_SUFFICIENT | INSUFFICIENT
    available: Decimal
    required: Decimal

    @property
    def deficit(self) -> Decimal:
        return max(self.required - self.available, Decimal(0))


class CdClient(Protocol):
    def check(self, broker_id: int, policy_id: int, required: Decimal, job_id: str) -> CdResult: ...


class UnavailableCdClient:
    def check(self, broker_id, policy_id, required, job_id):
        raise CdUnavailable("billing-service is not integrated: no CD client configured")


def get_cd_client() -> CdClient:
    return import_string(settings.CD_CLIENT)()


class GrpcCdClient:
    """gRPC client for billing-service. SYSTEM service token; every call carries a short deadline (design: 500 ms)."""

    _channel = None

    def __init__(self):
        import grpc
        from .gen import billing_cd_pb2 as pb
        from .gen import billing_cd_pb2_grpc as pbg

        self._grpc, self._pb = grpc, pb
        if GrpcCdClient._channel is None:
            GrpcCdClient._channel = grpc.insecure_channel(settings.BILLING_GRPC_TARGET)    # TLS with deployment work
        self._stub = pbg.CdBalanceStub(GrpcCdClient._channel)

    def check(self, broker_id, policy_id, required, job_id):
        grpc = self._grpc
        try:
            r = self._stub.CheckCdBalance(
                self._pb.CdBalanceRequest(policy_id=policy_id, broker_id=broker_id, required_amount=str(required), job_id=str(job_id)),
                timeout=settings.CD_GRPC_DEADLINE_SECONDS,
                metadata=(("authorization", f"Bearer {settings.BILLING_GRPC_TOKEN}"),))
        except grpc.RpcError as e:
            # Every failure is "could not determine the balance": the engine retries briefly, then parks the job visibly.
            raise CdUnavailable(f"{e.code().name}: {e.details()}") from e
        return CdResult(r.status, Decimal(r.available_balance), Decimal(r.required_amount))
