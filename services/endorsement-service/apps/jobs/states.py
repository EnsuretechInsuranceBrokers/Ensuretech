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

    # Legacy post-"done" lifecycle (EndorsementQueueStatus): an endorsement is not finished when the roster is updated;
    # it must reach the insurer, be confirmed by the TPA and yield an endorsement copy and e-cards.
    SENT_TO_INSURER = StateDef(620, Phase.INTEGRATION, Category.WAITING)
    AWAITING_TPA_CONFIRMATION = StateDef(630, Phase.INTEGRATION, Category.WAITING)
    TPA_CONFIRMATION_RECEIVED = StateDef(640, Phase.INTEGRATION, Category.SUCCESS)
    AWAITING_ENDORSEMENT_COPY = StateDef(650, Phase.INTEGRATION, Category.WAITING)
    ECARDS_AWAITED_FROM_TPA = StateDef(660, Phase.INTEGRATION, Category.WAITING)
    IC_REPORT_APPROVAL_PENDING = StateDef(670, Phase.INTEGRATION, Category.WAITING)
    IC_REPORT_UNDER_PROCESS = StateDef(671, Phase.INTEGRATION, Category.WAITING)
    IC_REPORT_DISCREPANCY_RAISED = StateDef(672, Phase.INTEGRATION, Category.BLOCKED)
    REUPLOAD_IN_PROGRESS = StateDef(680, Phase.INTEGRATION, Category.PROCESSING)

    COMPLETED = StateDef(900, Phase.FINAL, Category.SUCCESS, terminal=True)
    PARTIAL_PROCESSED = StateDef(910, Phase.FINAL, Category.PARTIAL, terminal=True)   # finished, but some rows were rejected along the way
    CLOSED = StateDef(920, Phase.FINAL, Category.SUCCESS, terminal=True)              # closed by an operator without waiting for the insurer/TPA
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
    # After the roster is updated the steps below can be skipped when an insurer/TPA does not take part, but never reordered.
    S.ENDORSED: frozenset({S.TPA_SYNC_QUEUED, S.SENT_TO_INSURER, S.COMPLETED, S.PARTIAL_PROCESSED, S.CLOSED}),
    S.TPA_SYNC_QUEUED: frozenset({S.TPA_SYNC_COMPLETED, S.TPA_SYNC_FAILED}),
    S.TPA_SYNC_FAILED: frozenset({S.CLOSED, S.TPA_SYNC_QUEUED, _MANUAL}),
    S.TPA_SYNC_COMPLETED: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.SENT_TO_INSURER, S.AWAITING_TPA_CONFIRMATION, S.COMPLETED}),
    S.SENT_TO_INSURER: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.AWAITING_TPA_CONFIRMATION, S.IC_REPORT_APPROVAL_PENDING, S.AWAITING_ENDORSEMENT_COPY,
                                  S.ECARDS_AWAITED_FROM_TPA, S.COMPLETED, _MANUAL}),
    S.AWAITING_TPA_CONFIRMATION: frozenset({S.CLOSED, S.TPA_CONFIRMATION_RECEIVED, _MANUAL}),
    S.TPA_CONFIRMATION_RECEIVED: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.AWAITING_ENDORSEMENT_COPY, S.ECARDS_AWAITED_FROM_TPA, S.COMPLETED}),
    S.AWAITING_ENDORSEMENT_COPY: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.ECARDS_AWAITED_FROM_TPA, S.COMPLETED, _MANUAL}),
    S.ECARDS_AWAITED_FROM_TPA: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.COMPLETED, _MANUAL}),
    S.IC_REPORT_APPROVAL_PENDING: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.IC_REPORT_UNDER_PROCESS, S.IC_REPORT_DISCREPANCY_RAISED, S.AWAITING_TPA_CONFIRMATION,
                                             S.AWAITING_ENDORSEMENT_COPY, S.ECARDS_AWAITED_FROM_TPA, S.COMPLETED, _MANUAL}),
    S.IC_REPORT_UNDER_PROCESS: frozenset({S.CLOSED, S.PARTIAL_PROCESSED, S.IC_REPORT_DISCREPANCY_RAISED, S.AWAITING_TPA_CONFIRMATION, S.AWAITING_ENDORSEMENT_COPY,
                                          S.ECARDS_AWAITED_FROM_TPA, S.COMPLETED, _MANUAL}),
    S.IC_REPORT_DISCREPANCY_RAISED: frozenset({S.CLOSED, S.REUPLOAD_IN_PROGRESS, _MANUAL}),
    S.REUPLOAD_IN_PROGRESS: frozenset({S.CLOSED, S.SENT_TO_INSURER, _MANUAL}),
    S.COMPLETED: frozenset(),
    S.PARTIAL_PROCESSED: frozenset(),
    S.CLOSED: frozenset(),
    _CANCEL: frozenset(),
    _MANUAL: frozenset({S.CLOSED, S.PREMIUM_CALC_QUEUED, S.CD_CHECK_INITIATED, S.ENDORSEMENT_QUEUED, S.TPA_SYNC_QUEUED, S.SENT_TO_INSURER,
                    S.AWAITING_TPA_CONFIRMATION, S.AWAITING_ENDORSEMENT_COPY, S.ECARDS_AWAITED_FROM_TPA, _CANCEL}),
}

# Every state must be a key, and terminal states must have no exits (enforced by tests too).
assert set(TRANSITIONS) == set(State)

# States the integration-service may drive through the internal lifecycle endpoint (everything after the roster update).
LIFECYCLE_STATES = frozenset(s for s in State if s.phase == Phase.INTEGRATION or s == State.COMPLETED)
