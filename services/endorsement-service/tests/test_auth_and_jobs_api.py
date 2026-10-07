import pytest
from rest_framework.test import APIClient

from apps.jobs.machine import transition
from apps.jobs.states import State

from .conftest import BROKER_A, BROKER_B, EMP_A, EMP_B, client_for, make_token, other_key

LIST = "/api/v1/broker/endorsements/"


def test_no_token_is_401():
    assert APIClient().get(LIST).status_code == 401


@pytest.mark.parametrize("kw", [
    {"key": "other"}, {"token_type": "refresh"}, {"issuer": "evil"}, {"ttl": -10},
])
def test_bad_tokens_are_401(kw):
    if kw.get("key") == "other":
        kw = {"key": other_key()}
    c = APIClient()
    c.credentials(HTTP_AUTHORIZATION=f"Bearer {make_token(**kw)}")
    assert c.get(LIST).status_code == 401


def test_hs256_token_signed_with_the_public_key_is_rejected():
    # classic algorithm-confusion attack: HMAC-sign using the public key text as the secret
    from django.conf import settings
    pub = open(settings.JWT_PUBLIC_KEY_PATH).read()
    import base64, hashlib, hmac, json, time

    def b64(b):
        return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = b64(json.dumps({"iss": settings.JWT_ISSUER, "token_type": "access", "role": "BROKER_ADMIN",
                           "broker_id": 1, "exp": int(time.time()) + 300}).encode())
    sig = b64(hmac.new(pub.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest())
    forged = f"{head}.{body}.{sig}"      # PyJWT refuses to build this itself; an attacker would not
    c = APIClient()
    c.credentials(HTTP_AUTHORIZATION=f"Bearer {forged}")
    assert c.get(LIST).status_code == 401


def test_gateways_are_role_separated(db):
    assert client_for("BROKER_ADMIN").get(LIST).status_code == 200
    assert client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A).get("/api/v1/employer/endorsements/").status_code == 200
    assert client_for("BROKER_ADMIN").get("/api/v1/employer/endorsements/").status_code == 403
    assert client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A).get(LIST).status_code == 403
    assert client_for("TPA_USER", None).get(LIST).status_code == 403
    assert client_for("SYSTEM", None).get(LIST).status_code == 403


def test_list_and_detail_are_tenant_scoped(make_job):
    mine = make_job()
    theirs = make_job(broker_id=BROKER_B, employer_id=EMP_B)
    c = client_for("BROKER_ADMIN", BROKER_A)
    body = c.get(LIST).json()
    assert [j["id"] for j in body["results"]] == [str(mine.id)]
    assert c.get(f"{LIST}{mine.id}/").status_code == 200
    for sub in ("", "audit/"):
        assert c.get(f"{LIST}{theirs.id}/{sub}").status_code == 404
    assert c.post(f"{LIST}{theirs.id}/cancel/").status_code == 404
    theirs.refresh_from_db()
    assert theirs.status == "REQUEST_RAISED"


def test_employer_user_only_sees_their_own_employers_jobs(make_job):
    mine = make_job(employer_id=EMP_A)
    sibling = make_job(employer_id=EMP_A + 1)       # same broker, different employer
    c = client_for("EMPLOYER_NON_ADMIN", BROKER_A, EMP_A)
    ids = [j["id"] for j in c.get("/api/v1/employer/endorsements/").json()["results"]]
    assert ids == [str(mine.id)]
    assert c.get(f"/api/v1/employer/endorsements/{sibling.id}/").status_code == 404


def test_list_filters_and_pagination(make_job):
    for i in range(5):
        make_job(policy_id=100 + i)
    c = client_for()
    assert c.get(LIST + "?policy_id=102").json()["count"] == 1
    page = c.get(LIST + "?page_size=2&page=3").json()
    assert page["count"] == 5 and len(page["results"]) == 1
    assert c.get(LIST + "?page_size=abc").status_code == 200


def test_audit_trail_endpoint(make_job):
    job = make_job()
    transition(job.id, State.FILE_STREAMED, actor="u1")
    rows = client_for().get(f"{LIST}{job.id}/audit/").json()
    assert [r["to_state"] for r in rows] == ["FILE_STREAMED"] and rows[0]["actor"] == "u1"


def test_cancel_permissions_and_state_rules(make_job):
    job = make_job()
    assert client_for("BROKER_NON_ADMIN").post(f"{LIST}{job.id}/cancel/").status_code == 403
    r = client_for("BROKER_ADMIN").post(f"{LIST}{job.id}/cancel/")
    assert r.status_code == 200 and r.json()["status"] == "CANCELLED_BY_HR"
    assert client_for("BROKER_ADMIN").post(f"{LIST}{job.id}/cancel/").status_code == 200   # repeat is idempotent
    done = make_job(status="COMPLETED", status_code=900, is_terminal=True)
    assert client_for("BROKER_ADMIN").post(f"{LIST}{done.id}/cancel/").status_code == 409  # finished another way

    running = make_job(status="ENDORSEMENT_QUEUED", status_code=500)
    assert client_for("BROKER_ADMIN").post(f"{LIST}{running.id}/cancel/").status_code == 409
