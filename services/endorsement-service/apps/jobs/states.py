"""Endorsement job states and the legal transition graph.

This is the subset the engine drives today (intake -> validation -> premium -> CD check -> endorse), plus the
integration/final states other services will drive. GUI-session lifecycle lives on GUISession, not on the job.
Codes are stable identifiers for dashboards/audit; do not renumber.
"""
from dataclasses import dataclass
from enum import Enum


class Phase(str, Enum):
    INIT = "INIT"
    VALIDATION = "VALIDATION"
    PREMIUM = "PREMIUM"
    CD = "CD"
    EXECUTION = "EXECUTION"
    INTEGRATION = "INTEGRATION"
    FINAL = "FINAL"
    ERROR = "ERROR"


class Category(str, Enum):
    UPLOAD = "UPLOAD"
    PROCESSING = "PROCESSING"
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"
    PARTIAL = "PARTIAL"
    WAITING = "WAITING"
    BLOCKED = "BLOCKED"
    CANCELLED = "CANCELLED"


@dataclass(frozen=True)
class StateDef:
    code: int
    phase: Phase
    category: Category
    terminal: bool = False


class State(Enum):
    REQUEST_RAISED = StateDef(100, Phase.INIT, Category.UPLOAD)
    FILE_STREAMED = StateDef(110, Phase.INIT, Category.UPLOAD)

    VALIDATION_QUEUED = StateDef(200, Phase.VALIDATION, Category.PROCESSING)
    VALIDATION_IN_PROGRESS = StateDef(210, Phase.VALIDATION, Category.PROCESSING)
    VALIDATION_PASSED = StateDef(220, Phase.VALIDATION, Category.SUCCESS)
    VALIDATION_PARTIAL = StateDef(230, Phase.VALIDATION, Category.PARTIAL)
    VALIDATION_FAILED = StateDef(240, Phase.VALIDATION, Category.ERROR, terminal=True)

    PREMIUM_CALC_QUEUED = StateDef(300, Phase.PREMIUM, Category.PROCESSING)
    PREMIUM_CALCULATED = StateDef(310, Phase.PREMIUM, Category.SUCCESS)
    PREMIUM_CALC_FAILED = StateDef(390, Phase.PREMIUM, Category.ERROR)

    CD_CHECK_INITIATED = StateDef(400, Phase.CD, Category.PROCESSING)
    CD_SUFFICIENT = StateDef(410, Phase.CD, Category.SUCCESS)
    CD_PARTIAL_SUFFICIENT = StateDef(420, Phase.CD, Category.BLOCKED)
    CD_INSUFFICIENT = StateDef(430, Phase.CD, Category.BLOCKED)
    CD_CHECK_FAILED = StateDef(490, Phase.CD, Category.ERROR)

    ENDORSEMENT_QUEUED = StateDef(500, Phase.EXECUTION, Category.PROCESSING)
    ENDORSEMENT_IN_PROGRESS = StateDef(510, Phase.EXECUTION, Category.PROCESSING)
    ENDORSED = StateDef(520, Phase.EXECUTION, Category.SUCCESS)
    ENDORSEMENT_FAILED = StateDef(590, Phase.EXECUTION, Category.ERROR)

    TPA_SYNC_QUEUED = StateDef(600, Phase.INTEGRATION, Category.PROCESSING)
    TPA_SYNC_COMPLETED = StateDef(610, Phase.INTEGRATION, Category.SUCCESS)
    TPA_SYNC_FAILED = StateDef(690, Phase.INTEGRATION, Category.ERROR)

    COMPLETED = StateDef(900, Phase.FINAL, Category.SUCCESS, terminal=True)
    CANCELLED_BY_HR = StateDef(950, Phase.FINAL, Category.CANCELLED, terminal=True)
    MANUAL_INTERVENTION_REQUIRED = StateDef(960, Phase.ERROR, Category.ERROR)

    @property
    def code(self) -> int:
        return self.value.code

    @property
    def phase(self) -> Phase:
        return self.value.phase

    @property
    def category(self) -> Category:
        return self.value.category

    @property
    def terminal(self) -> bool:
        return self.value.terminal


S = State
_MANUAL = S.MANUAL_INTERVENTION_REQUIRED
_CANCEL = S.CANCELLED_BY_HR

TRANSITIONS: dict[State, frozenset[State]] = {
    S.REQUEST_RAISED: frozenset({S.FILE_STREAMED, S.VALIDATION_FAILED, _CANCEL}),
    S.FILE_STREAMED: frozenset({S.VALIDATION_QUEUED, S.VALIDATION_FAILED, _CANCEL}),
    S.VALIDATION_QUEUED: frozenset({S.VALIDATION_IN_PROGRESS, _MANUAL, _CANCEL}),
    S.VALIDATION_IN_PROGRESS: frozenset({S.VALIDATION_PASSED, S.VALIDATION_PARTIAL, S.VALIDATION_FAILED, _MANUAL}),
    S.VALIDATION_PASSED: frozenset({S.PREMIUM_CALC_QUEUED, _CANCEL}),
    S.VALIDATION_PARTIAL: frozenset({S.PREMIUM_CALC_QUEUED, _CANCEL}),   # HR proceeds with the valid rows, or cancels
    S.VALIDATION_FAILED: frozenset(),
    S.PREMIUM_CALC_QUEUED: frozenset({S.PREMIUM_CALCULATED, S.PREMIUM_CALC_FAILED}),
    S.PREMIUM_CALC_FAILED: frozenset({S.PREMIUM_CALC_QUEUED, _MANUAL, _CANCEL}),
    S.PREMIUM_CALCULATED: frozenset({S.CD_CHECK_INITIATED, _CANCEL}),
    S.CD_CHECK_INITIATED: frozenset({S.CD_SUFFICIENT, S.CD_PARTIAL_SUFFICIENT, S.CD_INSUFFICIENT, S.CD_CHECK_FAILED}),
    S.CD_CHECK_FAILED: frozenset({S.CD_CHECK_INITIATED, _MANUAL, _CANCEL}),
    S.CD_PARTIAL_SUFFICIENT: frozenset({S.CD_CHECK_INITIATED, _CANCEL}),   # re-check after replenishment
    S.CD_INSUFFICIENT: frozenset({S.CD_CHECK_INITIATED, _CANCEL}),
    S.CD_SUFFICIENT: frozenset({S.ENDORSEMENT_QUEUED, _CANCEL}),
    S.ENDORSEMENT_QUEUED: frozenset({S.ENDORSEMENT_IN_PROGRESS}),          # no cancel once execution is queued
    S.ENDORSEMENT_IN_PROGRESS: frozenset({S.ENDORSED, S.ENDORSEMENT_FAILED}),
    S.ENDORSEMENT_FAILED: frozenset({S.ENDORSEMENT_QUEUED, _MANUAL}),
    S.ENDORSED: frozenset({S.TPA_SYNC_QUEUED, S.COMPLETED}),
    S.TPA_SYNC_QUEUED: frozenset({S.TPA_SYNC_COMPLETED, S.TPA_SYNC_FAILED}),
    S.TPA_SYNC_FAILED: frozenset({S.TPA_SYNC_QUEUED, _MANUAL}),
    S.TPA_SYNC_COMPLETED: frozenset({S.COMPLETED}),
    S.COMPLETED: frozenset(),
    _CANCEL: frozenset(),
    _MANUAL: frozenset({S.PREMIUM_CALC_QUEUED, S.CD_CHECK_INITIATED, S.ENDORSEMENT_QUEUED, S.TPA_SYNC_QUEUED, _CANCEL}),
}

# Every state must be a key, and terminal states must have no exits (enforced by tests too).
assert set(TRANSITIONS) == set(State)
