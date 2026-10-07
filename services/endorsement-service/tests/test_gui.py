import time

import pytest

from apps.intake.models import GuiSession, StagedRow
from apps.jobs.models import AuditTransition, EndorsementJob, EndorsementMember

from .conftest import BROKER_A, BROKER_B, EMP_A, add_member, client_for

pytestmark = pytest.mark.django_db(transaction=True)
G = "/api/v1/broker/endorsements/gui/sessions/"

ADD_SELF = {"employee_code": "2001", "relation": "Self", "first_name": "Asha", "last_name": "Rao", "dob": "17/05/1985",
            "gender": "F", "sum_insured": "500000"}


def new_session(c=None, policy_id=100, **kw):
    c = c or client_for()
    r = c.post(G, {"policy_id": policy_id, "effective_date": "2030-04-01", **kw}, format="json")
    assert r.status_code == 201, r.content
    return r.json()["session_id"]


def put(c, sid, key, op, row):
    return c.put(f"{G}{sid}/rows/{key}/", {"op_type": op, "row": row}, format="json")


def test_session_creation_returns_a_schema_driven_by_the_rules(world):
    r = client_for().post(G, {"policy_id": 100}, format="json")
    body = r.json()
    cols = {c["field"]: c for c in body["schema"]["columns"]}
    assert cols["dob"]["type"] == "date" and cols["dob"]["required_for"] == ["ADD"]
    assert cols["employee_code"]["required_for"] == ["ADD", "REMOVE", "UPDATE"]
    assert "SON_II" in body["schema"]["relations"] and body["schema"]["operations"] == ["ADD", "REMOVE", "UPDATE"]


@pytest.mark.parametrize("policy_id,code", [(102, 422), (999, 404), (200, 404)])
def test_session_needs_a_live_policy_of_the_callers_tenant(world, policy_id, code):
    assert client_for().post(G, {"policy_id": policy_id}, format="json").status_code == code


def test_live_validation_is_precise_stores_nothing_and_is_fast(world):
    c, sid = client_for(), new_session()
    bad = {**ADD_SELF, "pan": "SHORT", "dob": "2031-01-01", "first_name": ""}
    times = []
    for _ in range(15):
        t0 = time.perf_counter()
        r = c.post(f"{G}{sid}/rows/validate/", {"op_type": "ADD", "row": bad}, format="json")
        times.append((time.perf_counter() - t0) * 1000)
    body = r.json()
    assert body["valid"] is False
    assert {(i["field"], i["rule"]) for i in body["issues"]} >= {("pan", "INVALID_PAN"), ("dob", "DOB_IN_FUTURE"), ("first_name", "REQUIRED_FIELD")}
    assert GuiSession.objects.get(pk=sid).rows.count() == 0                              # validation is read-only
    times.sort()
    assert times[len(times) // 2] < 50, f"median {times[len(times) // 2]:.1f} ms"       # interactive budget
    ok = c.post(f"{G}{sid}/rows/validate/", {"op_type": "ADD", "row": ADD_SELF}, format="json").json()
    assert ok["valid"] is True and ok["normalised"]["dob"] == "1985-05-17" and ok["normalised"]["relation_code"] == "SELF"


def test_validation_knows_the_roster_and_the_session(world):
    add_member(code="2001", rel="SELF", pan="ABCDE1234F")
    c, sid = client_for(), new_session()
    r = c.post(f"{G}{sid}/rows/validate/", {"op_type": "ADD", "row": ADD_SELF}, format="json").json()
    assert "MEMBER_ALREADY_EXISTS" in {i["rule"] for i in r["issues"]}
    r = c.post(f"{G}{sid}/rows/validate/", {"op_type": "ADD", "row": {**ADD_SELF, "employee_code": "2002", "pan": "ABCDE1234F"}}, format="json").json()
    assert "DUPLICATE_PAN_EXISTING" in {i["rule"] for i in r["issues"]}
    r = c.post(f"{G}{sid}/rows/validate/", {"op_type": "REMOVE", "row": {"employee_code": "9999", "relation": "Self"}}, format="json").json()
    assert "MEMBER_NOT_FOUND" in {i["rule"] for i in r["issues"]}
    assert put(c, sid, "r1", "ADD", {**ADD_SELF, "employee_code": "3001"}).status_code == 201
    dup = put(c, sid, "r2", "ADD", {**ADD_SELF, "employee_code": "3001"}).json()
    assert "DUPLICATE_ROW_IN_SESSION" in {i["rule"] for i in dup["issues"]}
    # while both exist each clashes with the other; once the duplicate is gone, editing r1 in place must not flag it
    assert "DUPLICATE_ROW_IN_SESSION" in {i["rule"] for i in put(c, sid, "r1", "ADD", {**ADD_SELF, "employee_code": "3001"}).json()["issues"]}
    assert c.delete(f"{G}{sid}/rows/r2/").status_code == 204
    assert not put(c, sid, "r1", "ADD", {**ADD_SELF, "employee_code": "3001", "first_name": "Changed"}).json()["issues"]


def test_rate_limit_applies_per_session(world, settings):
    settings.GUI_VALIDATE_RATE_PER_MINUTE = 5
    from django.core.cache import cache
    cache.clear()
    c, other = client_for(), None
    sid, sid2 = new_session(c), new_session(c)
    codes = [c.post(f"{G}{sid}/rows/validate/", {"op_type": "ADD", "row": ADD_SELF}, format="json").status_code for _ in range(7)]
    assert codes == [200] * 5 + [429] * 2
    assert c.post(f"{G}{sid2}/rows/validate/", {"op_type": "ADD", "row": ADD_SELF}, format="json").status_code == 200   # other session unaffected


def test_staging_upsert_delete_and_listing(world):
    c, sid = client_for(), new_session()
    assert put(c, sid, "a", "ADD", ADD_SELF).status_code == 201
    assert put(c, sid, "a", "ADD", {**ADD_SELF, "first_name": "Priya"}).status_code == 200            # same key = update
    put(c, sid, "b", "ADD", {**ADD_SELF, "employee_code": "2002", "pan": "bad"})
    d = c.get(f"{G}{sid}/").json()
    assert d["row_count"] == 2 and d["rows_with_errors"] == 1 and d["rows"][0]["row"]["first_name"] == "Priya"
    assert c.delete(f"{G}{sid}/rows/b/").status_code == 204 and c.delete(f"{G}{sid}/rows/b/").status_code == 404
    assert c.get(f"{G}{sid}/").json()["row_count"] == 1
    assert put(c, sid, "z", "COMBINE", ADD_SELF).status_code == 400
    assert c.put(f"{G}{sid}/rows/z/", {"op_type": "ADD", "row": "nope"}, format="json").status_code == 400


def test_submit_splits_a_mixed_session_into_one_typed_job_per_operation(flow):
    add_member(code="5001", rel="SELF"); add_member(code="5002", rel="SELF")
    c, sid = client_for(), new_session()
    put(c, sid, "1", "ADD", ADD_SELF)
    put(c, sid, "2", "ADD", {"employee_code": "2001", "relation": "Spouse", "first_name": "Ravi", "dob": "02-Feb-1983", "sum_insured": "500000"})
    put(c, sid, "3", "REMOVE", {"employee_code": "5001", "relation": "Self"})
    put(c, sid, "4", "UPDATE", {"employee_code": "5002", "relation": "Self", "grade": "A"})
    r = c.post(f"{G}{sid}/submit/")
    assert r.status_code == 202, r.content
    jobs = {j["op_type"]: j for j in r.json()["jobs"]}
    assert set(jobs) == {"ADD", "REMOVE", "UPDATE"} and jobs["ADD"]["rows"] == 2
    assert jobs["ADD"]["progress_url"].endswith(f"{jobs['ADD']['job_id']}/progress/")
    for op, j in jobs.items():
        job = EndorsementJob.objects.get(pk=j["job_id"])
        assert job.op_type == op and job.source_type == "GUI" and str(job.parent_session_id) == sid
        assert job.status == "ENDORSED", (op, job.status, job.error_summary)
    assert EndorsementMember.objects.get(employee_code="2001", relation_code="SPOUSE").first_name == "Ravi"
    assert EndorsementMember.objects.get(employee_code="5001").is_active is False
    assert EndorsementMember.objects.get(employee_code="5002").grade == "A"
    assert GuiSession.objects.get(pk=sid).state == "SUBMITTED"


def test_submit_is_idempotent_and_locks_the_session(world):
    c, sid = client_for(), new_session()
    put(c, sid, "1", "ADD", ADD_SELF)
    first = c.post(f"{G}{sid}/submit/")
    again = c.post(f"{G}{sid}/submit/")
    assert first.status_code == 202 and again.status_code == 200
    assert first.json()["jobs"][0]["job_id"] == again.json()["jobs"][0]["job_id"]
    assert EndorsementJob.objects.count() == 1
    assert put(c, sid, "2", "ADD", ADD_SELF).status_code == 409                       # no edits after submit
    assert c.post(f"{G}{sid}/abandon/").status_code == 409


def test_submit_refuses_rows_that_still_have_errors_and_nothing_is_created(world):
    c, sid = client_for(), new_session()
    put(c, sid, "ok", "ADD", ADD_SELF)
    put(c, sid, "bad", "ADD", {**ADD_SELF, "employee_code": "2002", "pan": "bad"})
    r = c.post(f"{G}{sid}/submit/")
    assert r.status_code == 422
    assert [x["row_key"] for x in r.json()["rows"]] == ["bad"]
    assert EndorsementJob.objects.count() == 0 and GuiSession.objects.get(pk=sid).state == "OPEN"
    assert c.post(f"{G}{new_session(c)}/submit/").status_code == 422                    # empty session


def test_submit_rechecks_against_the_roster_as_it_is_now(world):
    c, sid = client_for(), new_session()
    put(c, sid, "1", "ADD", ADD_SELF)                                                    # valid when staged
    add_member(code="2001", rel="SELF")                                                  # someone adds the same person meanwhile
    r = c.post(f"{G}{sid}/submit/")
    assert r.status_code == 422 and r.json()["rows"][0]["issues"][0]["rule"] == "MEMBER_ALREADY_EXISTS"


def test_gui_jobs_run_on_the_priority_queue(world, monkeypatch):
    seen = []
    import apps.validation.tasks as vt
    orig = vt.validate_chunk.apply_async
    monkeypatch.setattr(vt.validate_chunk, "apply_async", lambda *a, **k: (seen.append(k.get("queue")), orig(*a, **k))[1])
    c, sid = client_for(), new_session()
    put(c, sid, "1", "ADD", ADD_SELF)
    c.post(f"{G}{sid}/submit/")
    assert seen == ["validation.high"]


def test_family_rules_still_apply_to_gui_submissions_via_the_pipeline(world):
    c, sid = client_for(), new_session()
    put(c, sid, "1", "ADD", ADD_SELF)
    put(c, sid, "2", "ADD", {"employee_code": "2001", "relation": "Father", "first_name": "Old", "dob": "1950-01-01", "sum_insured": "500000"})
    jobs = c.post(f"{G}{sid}/submit/").json()["jobs"]
    job = EndorsementJob.objects.get(pk=jobs[0]["job_id"])
    assert job.status == "VALIDATION_PARTIAL"                                           # FATHER is not covered by the construct


# ── lifecycle and isolation ──────────────────────────────────────────────────────────────────────────────────
def test_abandon_and_expiry(world):
    from datetime import timedelta
    from django.utils import timezone
    from apps.intake.tasks import expire_gui_sessions
    c = client_for()
    a = new_session(c)
    assert c.post(f"{G}{a}/abandon/").json()["state"] == "ABANDONED"
    assert put(c, a, "1", "ADD", ADD_SELF).status_code == 409
    b = new_session(c)
    GuiSession.objects.filter(pk=b).update(expires_at=timezone.now() - timedelta(minutes=1))
    assert put(c, b, "1", "ADD", ADD_SELF).status_code == 410
    assert GuiSession.objects.get(pk=b).state == "ABANDONED"
    assert c.post(f"{G}{b}/submit/").status_code == 409
    d = new_session(c)
    GuiSession.objects.filter(pk=d).update(expires_at=timezone.now() - timedelta(minutes=1))
    assert expire_gui_sessions.apply().get() == 1 and GuiSession.objects.get(pk=d).state == "ABANDONED"


def test_activity_extends_the_session(world):
    from datetime import timedelta
    from django.utils import timezone
    c, sid = client_for(), new_session()
    soon = timezone.now() + timedelta(minutes=2)
    GuiSession.objects.filter(pk=sid).update(expires_at=soon)
    put(c, sid, "1", "ADD", ADD_SELF)
    assert GuiSession.objects.get(pk=sid).expires_at > soon + timedelta(hours=3)


def test_sessions_are_tenant_scoped(world):
    c, sid = client_for(), new_session()
    put(c, sid, "1", "ADD", ADD_SELF)
    other = client_for("BROKER_ADMIN", BROKER_B)
    for call in (lambda: other.get(f"{G}{sid}/"), lambda: other.post(f"{G}{sid}/rows/validate/", {"op_type": "ADD", "row": ADD_SELF}, format="json"),
                 lambda: put(other, sid, "9", "ADD", ADD_SELF), lambda: other.post(f"{G}{sid}/submit/"), lambda: other.post(f"{G}{sid}/abandon/"),
                 lambda: other.delete(f"{G}{sid}/rows/1/")):
        assert call().status_code == 404
    sibling = client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A + 7)
    assert sibling.get(f"/api/v1/employer/endorsements/gui/sessions/{sid}/").status_code == 404
    mine = client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A)
    r = mine.post("/api/v1/employer/endorsements/gui/sessions/", {"policy_id": 100}, format="json")
    assert r.status_code == 201
    assert mine.post("/api/v1/employer/endorsements/gui/sessions/", {"policy_id": 103}, format="json").status_code == 404   # another employer's policy


def test_read_only_roles_cannot_use_the_editor(world):
    assert client_for("BROKER_NON_ADMIN").post(G, {"policy_id": 100}, format="json").status_code == 403
