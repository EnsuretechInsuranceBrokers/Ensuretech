import pytest

from apps.mail.models import Contact, Notification, Status, Template, TriggerSetting

from .conftest import client_for

pytestmark = pytest.mark.django_db
B = "/api/v1/broker/notifications/"
A, A_NON, B2 = (lambda: client_for("BROKER_ADMIN", 1)), (lambda: client_for("BROKER_NON_ADMIN", 1)), (lambda: client_for("BROKER_ADMIN", 2))


def test_gateway_and_role_rules():
    assert client_for("EMPLOYER_ADMIN", 1, 10).get(B + "log/").status_code == 403
    assert client_for("SYSTEM", None).get(B + "log/").status_code == 403
    assert client_for("BROKER_ADMIN", None).get(B + "log/").status_code == 403
    from rest_framework.test import APIClient
    assert APIClient().get(B + "log/").status_code == 401
    body = {"role": "BROKER_SPOC", "email": "x@y.com"}
    assert A_NON().post(B + "contacts/", body, format="json").status_code == 403
    assert A_NON().get(B + "contacts/").status_code == 200
    assert A_NON().put(B + "trigger-settings/", {"trigger_code": "CD_BALANCE_LOW", "email_enabled": False}, format="json").status_code == 403


def test_contacts_are_validated_idempotent_and_tenant_scoped():
    c = A()
    r = c.post(B + "contacts/", {"role": "EMPLOYER_SPOC", "email": "HR@Acme.com", "employer_id": 10, "name": "Hari"}, format="json")
    assert r.status_code == 201 and r.json()["email"] == "hr@acme.com"
    assert c.post(B + "contacts/", {"role": "EMPLOYER_SPOC", "email": "hr@acme.com", "employer_id": 10}, format="json").status_code == 200
    for bad in ({"role": "EMPLOYER_SPOC", "email": "nope"}, {"role": "EMPLOYER_SPOC", "email": "a@b.com\r\nBcc: x@y.com"}, {"role": "KING", "email": "a@b.com"},
                {"role": "TPA", "email": "a@b.com", "name": "<b>x"}, {"role": "TPA", "email": "a@b.com", "policy_id": "1"}, {"role": "TPA"}):
        assert c.post(B + "contacts/", bad, format="json").status_code == 400, bad
    assert B2().get(B + "contacts/").json() == []
    assert B2().delete(B + f"contacts/{r.json()['id']}/").status_code == 404
    assert c.delete(B + f"contacts/{r.json()['id']}/").status_code == 204


def test_trigger_catalogue_and_settings():
    c = A()
    codes = {t["code"] for t in c.get(B + "triggers/").json()}
    assert {"ENDORSEMENT_FAILED", "CD_BALANCE_LOW", "TPA_SYNC_FAILED"} <= codes
    assert c.put(B + "trigger-settings/", {"trigger_code": "NOPE", "email_enabled": True}, format="json").status_code == 400
    assert c.put(B + "trigger-settings/", {"trigger_code": "CD_BALANCE_LOW", "email_enabled": "yes"}, format="json").status_code == 400
    assert c.put(B + "trigger-settings/", {"trigger_code": "CD_BALANCE_LOW", "email_enabled": False}, format="json").status_code == 200
    assert c.put(B + "trigger-settings/", {"trigger_code": "CD_BALANCE_LOW", "policy_id": 7, "email_enabled": True}, format="json").status_code == 200
    t = next(t for t in c.get(B + "triggers/").json() if t["code"] == "CD_BALANCE_LOW")
    assert t["broker_default"] is False and t["policy_overrides"] == {"7": True}
    assert TriggerSetting.objects.filter(broker_id=2).count() == 0


def test_templates_are_validated_previewed_and_tenant_scoped():
    c = A()
    good = {"trigger_code": "CD_BALANCE_LOW", "subject": "Low: {{policy_number}}", "body_html": "<p>{{available}} / {{threshold}}</p>"}
    r = c.post(B + "templates/", good, format="json")
    assert r.status_code == 201
    for bad, why in (({**good, "body_html": "<p>{{secret}}</p>"}, "unknown placeholder"), ({**good, "body_html": "<script>x</script>"}, "script"),
                     ({**good, "body_html": '<a href="javascript:alert(1)">x</a>'}, "js url"), ({**good, "body_html": "<img onerror=alert(1)>"}, "handler"),
                     ({**good, "subject": "a\nb"}, "newline"), ({**good, "trigger_code": "NOPE"}, "trigger"), ({**good, "subject": ""}, "empty"),
                     ({**good, "body_html": "{{ 7*7 }}"}, "expression")):
        assert c.post(B + "templates/", bad, format="json").status_code == 400, why
    p = c.post(B + "templates/preview/", {**good, "body_html": "<p>{{policy_number}} {{available}}</p>"}, format="json").json()
    assert p["subject"] == "Low: P-12345" and "&lt;available&gt;" in p["body_html"]
    assert B2().put(B + f"templates/{r.json()['id']}/", good, format="json").status_code == 404
    assert c.put(B + f"templates/{r.json()['id']}/", {**good, "subject": "New {{policy_number}}", "is_active": False}, format="json").json()["is_active"] is False
    assert Template.objects.get(pk=r.json()["id"]).subject == "New {{policy_number}}"


def test_log_is_tenant_scoped_filterable_and_retry_is_dead_only():
    mine = Notification.objects.create(dedupe_key="a", trigger_code="CD_BALANCE_LOW", broker_id=1, policy_id=1, to_email="a@b.com", subject="s", body_html="<p>b</p>",
                                       status=Status.DEAD, attempts=3, last_error="550")
    Notification.objects.create(dedupe_key="b", trigger_code="CD_BALANCE_LOW", broker_id=2, to_email="z@b.com", subject="s", body_html="b")
    c = A()
    assert [n["id"] for n in c.get(B + "log/").json()["results"]] == [str(mine.id)]
    assert c.get(B + "log/?status=SENT").json()["count"] == 0 and c.get(B + "log/?policy_id=1").json()["count"] == 1
    assert c.get(B + "log/?policy_id=x").status_code == 400
    assert c.get(B + f"log/{mine.id}/").json()["body_html"] == "<p>b</p>"
    assert B2().get(B + f"log/{mine.id}/").status_code == 404 and B2().post(B + f"log/{mine.id}/retry/").status_code == 404
    assert A_NON().post(B + f"log/{mine.id}/retry/").status_code == 403
    r = c.post(B + f"log/{mine.id}/retry/")
    assert r.json()["status"] == "PENDING" and r.json()["attempts"] == 0
    assert c.post(B + f"log/{mine.id}/retry/").status_code == 409
