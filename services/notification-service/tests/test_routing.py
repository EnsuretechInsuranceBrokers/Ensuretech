import pytest

from apps.mail import routing
from apps.mail.models import Contact, Notification, Template, TriggerSetting

from .conftest import sample

pytestmark = pytest.mark.django_db


def contacts(broker=1, employer=10, policy=1):
    Contact.objects.create(broker_id=broker, role="BROKER_SPOC", email="broker@b.com", name="Bea")
    Contact.objects.create(broker_id=broker, employer_id=employer, role="EMPLOYER_SPOC", email="hr@e.com")
    Contact.objects.create(broker_id=broker, employer_id=employer, policy_id=policy, role="EMPLOYER_SPOC", email="policy-hr@e.com")
    Contact.objects.create(broker_id=broker, employer_id=99, role="EMPLOYER_SPOC", email="other-employer@e.com")
    Contact.objects.create(broker_id=2, role="BROKER_SPOC", email="other-broker@b.com")


def state(to_state, **kw):
    e = sample("endorsement-state-changed")
    return {**e, "to_state": to_state, **kw}


def test_default_enabled_triggers_reach_exactly_the_scoped_contacts():
    contacts()
    assert routing.process_event(state("CD_INSUFFICIENT")) == 3
    got = sorted(Notification.objects.values_list("to_email", flat=True))
    assert got == ["broker@b.com", "hr@e.com", "policy-hr@e.com"]                         # not the other employer, not the other broker


def test_triggers_that_are_off_by_default_send_nothing_until_enabled():
    contacts()
    assert routing.process_event(state("ENDORSED")) == 0
    TriggerSetting.objects.create(broker_id=1, policy_id=None, trigger_code="ENDORSEMENT_COMPLETED", email_enabled=True)
    assert routing.process_event(state("ENDORSED", event_id="e2")) == 3
    TriggerSetting.objects.create(broker_id=1, policy_id=1, trigger_code="ENDORSEMENT_COMPLETED", email_enabled=False)     # policy beats broker
    assert routing.process_event(state("ENDORSED", event_id="e3")) == 0
    TriggerSetting.objects.create(broker_id=1, policy_id=None, trigger_code="ENDORSEMENT_FAILED", email_enabled=False)      # broker beats built-in
    assert routing.process_event(state("PREMIUM_CALC_FAILED", event_id="e4")) == 0


@pytest.mark.parametrize("to_state,code", [("REQUEST_RAISED", "ENDORSEMENT_REQUEST_RAISED"), ("VALIDATION_PARTIAL", "ENDORSEMENT_NEEDS_REVIEW"),
                                           ("VALIDATION_FAILED", "ENDORSEMENT_FAILED"), ("CD_CHECK_FAILED", "ENDORSEMENT_FAILED"),
                                           ("CD_PARTIAL_SUFFICIENT", "ENDORSEMENT_BLOCKED_CD"), ("ENDORSED", "ENDORSEMENT_COMPLETED")])
def test_state_changes_map_to_triggers(to_state, code):
    assert routing._triggers_for(state(to_state)) == [code]


def test_unmapped_states_and_unrelated_events_are_ignored():
    for s in ("FILE_STREAMED", "CANCELLED_BY_HR", "VALIDATION_QUEUED", "COMPLETED"):
        assert routing._triggers_for(state(s)) == []
    assert routing._triggers_for({"event_type": "RateCardChanged"}) == []
    assert routing._triggers_for({**sample("endorsement-applied"), "members_changed": 0}) == []


def test_applied_events_pick_the_member_trigger_by_operation():
    for op, code in (("ADD", "MEMBER_ADDED"), ("UPDATE", "MEMBER_UPDATED"), ("REMOVE", "MEMBER_REMOVED")):
        assert routing._triggers_for({**sample("endorsement-applied"), "op_type": op}) == [code]


def test_a_replayed_event_creates_nothing_new_and_policy_number_comes_from_policy_events():
    contacts()
    routing.process_event(sample("policy-approved"))                                       # teaches the directory policy "E2E-GMC-1"; also queues POLICY_APPROVED
    before = Notification.objects.count()
    assert routing.process_event(state("CD_INSUFFICIENT")) == 3
    assert routing.process_event(state("CD_INSUFFICIENT")) == 0
    assert Notification.objects.count() == before + 3
    assert "E2E-GMC-1" in Notification.objects.filter(trigger_code="ENDORSEMENT_BLOCKED_CD").first().subject


def test_cd_and_tpa_events_route_with_their_own_context():
    contacts()
    low = {**sample("cd-balance-updated"), "event_type": "CdBalanceLow"}
    assert routing.process_event(low) == 3
    n = Notification.objects.get(trigger_code="CD_BALANCE_LOW", to_email="broker@b.com")
    assert "3234.85" in n.body_html and "5000.00" in n.body_html
    assert routing.process_event(sample("tpa-sync-failed")) == 1                           # broker SPOC only
    assert "HTTP 503" in Notification.objects.get(trigger_code="TPA_SYNC_FAILED").body_html


def test_custom_templates_by_specificity_and_values_are_escaped():
    contacts()
    Template.objects.create(broker_id=1, trigger_code="ENDORSEMENT_BLOCKED_CD", subject="BROKER {{policy_number}}", body_html="<p>broker wide {{state}}</p>")
    Template.objects.create(broker_id=1, employer_id=10, trigger_code="ENDORSEMENT_BLOCKED_CD", subject="EMPLOYER {{policy_number}}", body_html="<p>emp</p>")
    Template.objects.create(broker_id=1, policy_id=1, trigger_code="ENDORSEMENT_BLOCKED_CD", subject="POLICY <{{policy_number}}>", body_html="<p>{{job_id}}</p>")
    routing.process_event(state("CD_INSUFFICIENT", job_id="<script>alert(1)</script>"))
    n = Notification.objects.first()
    assert n.subject.startswith("POLICY") and "&lt;script&gt;" in n.body_html and "<script>" not in n.body_html
    Template.objects.filter(policy_id=1).update(is_active=False)
    Notification.objects.all().delete()
    routing.process_event(state("CD_INSUFFICIENT", event_id="e9"))
    assert Notification.objects.first().subject.startswith("EMPLOYER")


def test_newline_in_event_data_cannot_break_the_subject():
    contacts()
    Template.objects.create(broker_id=1, trigger_code="ENDORSEMENT_BLOCKED_CD", subject="Hold {{job_id}}", body_html="<p>x</p>")
    routing.process_event(state("CD_INSUFFICIENT", job_id="J1\r\nBcc: evil@x.com"))
    assert "\n" not in Notification.objects.first().subject and "\r" not in Notification.objects.first().subject


@pytest.mark.parametrize("bad", [[1], {"event_type": "EndorsementStateChanged", "to_state": "ENDORSED", "event_id": "x", "broker_id": "1", "employer_id": 1},
                                 {"event_type": "PolicyApproved", "broker_id": 1}, state("ENDORSED", event_id="")])
def test_malformed_events_are_poison(bad):
    with pytest.raises(routing.PoisonEvent):
        routing.process_event(bad)
