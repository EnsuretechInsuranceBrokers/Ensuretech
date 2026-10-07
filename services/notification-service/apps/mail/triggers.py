"""The catalogue of mails the platform can send. Each trigger names its recipients' roles, the placeholders a template may use,
and a default subject/body. The legacy system had ~120 of these; this is the set our events can drive today. Adding one is adding
an entry here and a rule in routing.py."""
from dataclasses import dataclass

from .models import Role


@dataclass(frozen=True)
class TriggerDef:
    code: str
    label: str
    roles: tuple
    placeholders: tuple
    subject: str
    body: str
    default_enabled: bool = False


COMMON = ("policy_number", "policy_id", "brand")
ENDORSEMENT = COMMON + ("job_id", "op_type", "source_type", "state")
_SPOCS = (Role.BROKER_SPOC.value, Role.EMPLOYER_SPOC.value)

REGISTRY: dict[str, TriggerDef] = {t.code: t for t in [
    TriggerDef("ENDORSEMENT_REQUEST_RAISED", "Endorsement request raised", _SPOCS, ENDORSEMENT,
               "Endorsement request received for policy {{policy_number}}",
               "<p>An endorsement request ({{op_type}}, via {{source_type}}) was raised for policy <b>{{policy_number}}</b>.</p><p>Reference: {{job_id}}</p>"),
    TriggerDef("ENDORSEMENT_NEEDS_REVIEW", "Endorsement needs review (discrepancies)", _SPOCS, ENDORSEMENT,
               "Action needed: discrepancies in endorsement for policy {{policy_number}}",
               "<p>Some rows of the endorsement for policy <b>{{policy_number}}</b> failed validation. Review the error sheet and confirm to continue "
               "with the valid rows, or cancel.</p><p>Reference: {{job_id}}</p>", default_enabled=True),
    TriggerDef("ENDORSEMENT_COMPLETED", "Endorsement completed", _SPOCS, ENDORSEMENT,
               "Endorsement completed for policy {{policy_number}}",
               "<p>The endorsement ({{op_type}}) for policy <b>{{policy_number}}</b> has been applied.</p><p>Reference: {{job_id}}</p>"),
    TriggerDef("ENDORSEMENT_FAILED", "Endorsement failed", _SPOCS, ENDORSEMENT,
               "Endorsement failed for policy {{policy_number}}",
               "<p>The endorsement for policy <b>{{policy_number}}</b> could not be completed (state: {{state}}).</p><p>Reference: {{job_id}}</p>",
               default_enabled=True),
    TriggerDef("ENDORSEMENT_BLOCKED_CD", "Endorsement blocked: insufficient deposit", _SPOCS, ENDORSEMENT,
               "Endorsement on hold for policy {{policy_number}}: top up the deposit",
               "<p>The endorsement for policy <b>{{policy_number}}</b> is on hold because the client deposit does not cover it. "
               "It continues once the deposit is topped up and the job is retried.</p><p>Reference: {{job_id}}</p>", default_enabled=True),
    TriggerDef("MEMBER_ADDED", "Members added", _SPOCS, ENDORSEMENT + ("members_changed", "total"),
               "{{members_changed}} member(s) added to policy {{policy_number}}",
               "<p>{{members_changed}} member(s) were added to policy <b>{{policy_number}}</b>. Total charged: {{total}}.</p>"),
    TriggerDef("MEMBER_UPDATED", "Members updated", _SPOCS, ENDORSEMENT + ("members_changed", "total"),
               "{{members_changed}} member record(s) corrected on policy {{policy_number}}",
               "<p>{{members_changed}} member record(s) were corrected on policy <b>{{policy_number}}</b>.</p>"),
    TriggerDef("MEMBER_REMOVED", "Members removed", _SPOCS, ENDORSEMENT + ("members_changed", "total"),
               "{{members_changed}} member(s) removed from policy {{policy_number}}",
               "<p>{{members_changed}} member(s) were removed from policy <b>{{policy_number}}</b>. Adjustment: {{total}}.</p>"),
    TriggerDef("CD_BALANCE_LOW", "Client deposit below threshold", _SPOCS, COMMON + ("available", "threshold"),
               "Client deposit low for policy {{policy_number}}",
               "<p>The available client deposit for policy <b>{{policy_number}}</b> is {{available}}, below the threshold of {{threshold}}. "
               "Please arrange a top-up to avoid endorsements being held.</p>", default_enabled=True),
    TriggerDef("POLICY_APPROVED", "Policy approved and live", _SPOCS, COMMON,
               "Policy {{policy_number}} is live", "<p>Policy <b>{{policy_number}}</b> has been approved and is now live.</p>", default_enabled=True),
    TriggerDef("TPA_SYNC_FAILED", "TPA sync failed", (Role.BROKER_SPOC.value,), COMMON + ("job_id", "error", "attempts"),
               "TPA sync failed for policy {{policy_number}}",
               "<p>Sending endorsement <b>{{job_id}}</b> to the TPA failed after {{attempts}} attempt(s): {{error}}.</p>"
               "<p>It is held for an operator retry.</p>", default_enabled=True),
]}
