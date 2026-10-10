"""Event -> notifications. Pure policy: which triggers an event raises, who gets each, which wording, and an idempotent insert.
Events whose type or state we do not map are ignored. Malformed events raise PoisonEvent (the consumer dead-letters them)."""
import logging

from django.conf import settings
from django.db import IntegrityError, transaction
from django.db.models import Q

from . import render
from .models import Contact, Notification, PolicyDirectory, Template, TriggerSetting
from .triggers import REGISTRY

log = logging.getLogger(__name__)

FAILED_STATES = {"VALIDATION_FAILED", "PREMIUM_CALC_FAILED", "CD_CHECK_FAILED", "ENDORSEMENT_FAILED", "MANUAL_INTERVENTION_REQUIRED"}
CD_BLOCKED = {"CD_INSUFFICIENT", "CD_PARTIAL_SUFFICIENT"}
OP_TRIGGER = {"ADD": "MEMBER_ADDED", "UPDATE": "MEMBER_UPDATED", "REMOVE": "MEMBER_REMOVED"}


class PoisonEvent(ValueError):
    pass


def _int(e, key):
    v = e.get(key)
    if isinstance(v, bool) or not isinstance(v, int):
        raise PoisonEvent(f"{e.get('event_type')}: {key} must be an integer")
    return v


def _triggers_for(e: dict) -> list[str]:
    t = e.get("event_type")
    if t == "EndorsementStateChanged":
        s = e.get("to_state")
        if s == "REQUEST_RAISED":
            return ["ENDORSEMENT_REQUEST_RAISED"]
        if s == "VALIDATION_PARTIAL":
            return ["ENDORSEMENT_NEEDS_REVIEW"]
        if s in FAILED_STATES:
            return ["ENDORSEMENT_FAILED"]
        if s in CD_BLOCKED:
            return ["ENDORSEMENT_BLOCKED_CD"]
        if s == "ENDORSED":
            return ["ENDORSEMENT_COMPLETED"]
    elif t == "EndorsementApplied":
        return [OP_TRIGGER[e["op_type"]]] if e.get("op_type") in OP_TRIGGER and (e.get("members_changed") or 0) > 0 else []
    elif t == "CdBalanceLow":
        return ["CD_BALANCE_LOW"]
    elif t == "PolicyApproved":
        return ["POLICY_APPROVED"]
    elif t == "TpaSyncFailed":
        return ["TPA_SYNC_FAILED"]
    return []


def _learn_policy(e: dict):
    if e.get("event_type") in ("PolicyCreated", "PolicyApproved", "PolicyDeactivated") and isinstance(e.get("policy_id"), int):
        PolicyDirectory.objects.update_or_create(policy_id=e["policy_id"], defaults={
            "broker_id": _int(e, "broker_id"), "employer_id": _int(e, "employer_id"), "policy_number": str(e.get("policy_number") or "")[:60]})


def enabled(broker_id: int, policy_id, code: str) -> bool:
    rows = {r.policy_id: r.email_enabled for r in TriggerSetting.objects.filter(broker_id=broker_id, trigger_code=code)
            .filter(Q(policy_id=policy_id) | Q(policy_id__isnull=True))}
    if policy_id in rows:
        return rows[policy_id]
    if None in rows:
        return rows[None]
    return REGISTRY[code].default_enabled


def recipients(broker_id, employer_id, policy_id, roles) -> list[Contact]:
    q = Q(policy_id=policy_id) if policy_id else Q(pk__in=[])
    q |= Q(policy_id__isnull=True, employer_id=employer_id) if employer_id else Q(pk__in=[])
    q |= Q(policy_id__isnull=True, employer_id__isnull=True)
    seen, out = set(), []
    for c in Contact.objects.filter(q, broker_id=broker_id, is_active=True, role__in=roles).order_by("id"):
        if c.email.lower() not in seen:
            seen.add(c.email.lower())
            out.append(c)
    return out


def pick_template(broker_id, employer_id, policy_id, code):
    base = Template.objects.filter(broker_id=broker_id, trigger_code=code, is_active=True).order_by("-id")
    for flt in ({"policy_id": policy_id} if policy_id else None, {"employer_id": employer_id, "policy_id__isnull": True} if employer_id else None,
                {"employer_id__isnull": True, "policy_id__isnull": True}):
        if flt and (t := base.filter(**flt).first()):
            return t
    return None


def context_for(e: dict, directory: PolicyDirectory | None) -> dict:
    ctx = {k: ("" if e.get(k) is None else str(e[k])) for k in
           ("job_id", "op_type", "source_type", "members_changed", "total", "available", "threshold", "error", "attempts", "policy_id")}
    ctx["state"] = str(e.get("to_state") or "").replace("_", " ").title()
    ctx["policy_number"] = (directory.policy_number if directory and directory.policy_number else f"#{e.get('policy_id')}")
    ctx["job_id"] = ctx["job_id"] or str(e.get("endorsement_job_id") or "")
    return ctx


def process_event(e: dict) -> int:
    """Returns how many notifications were created."""
    if not isinstance(e, dict):
        raise PoisonEvent("event is not a JSON object")
    _learn_policy(e)
    codes = _triggers_for(e)
    if not codes:
        return 0
    eid = e.get("event_id")
    if not isinstance(eid, str) or not eid:
        raise PoisonEvent("event has no event_id")
    broker, employer, policy = _int(e, "broker_id"), _int(e, "employer_id"), e.get("policy_id")
    directory = PolicyDirectory.objects.filter(pk=policy).first() if isinstance(policy, int) else None
    created = 0
    for code in codes:
        trig = REGISTRY[code]
        if not enabled(broker, policy, code):
            continue
        ctx = {**context_for(e, directory), "brand": settings.MAIL_BRAND}
        tpl = pick_template(broker, employer, policy, code)
        subject = render.render_subject(tpl.subject if tpl else trig.subject, ctx)
        body = render.render_body(tpl.body_html if tpl else trig.body, ctx)
        for c in recipients(broker, employer, policy, trig.roles):
            try:
                with transaction.atomic():
                    Notification.objects.create(dedupe_key=f"{eid}:{code}:{c.email.lower()}"[:200], trigger_code=code, broker_id=broker,
                                                employer_id=employer, policy_id=policy if isinstance(policy, int) else None, to_email=c.email,
                                                to_name=c.name, subject=subject, body_html=body, template_id=tpl.id if tpl else None)
                created += 1
            except IntegrityError:
                pass                                                    # replayed event: already queued
    return created
