"""Contingency Deposit (CD) balance check. billing-service owns CD accounts and does not exist yet, so the default
client says so honestly instead of pretending: a job that reaches the CD step parks at CD_CHECK_FAILED until a real
client is configured (settings.CD_CLIENT)."""
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
