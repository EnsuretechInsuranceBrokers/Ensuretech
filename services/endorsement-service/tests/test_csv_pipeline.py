import pytest

from apps.computation.policy_client import PolicyAccessError, PolicyUnavailable
from apps.intake.models import StagedRow
from apps.jobs.models import AuditTransition, EndorsementJob, EndorsementMember
from apps.validation.models import ChunkState, ValidationLog

from .conftest import BROKER_A, BROKER_B, EMP_A, EMP_B, client_for, csv_text, upload

pytestmark = pytest.mark.django_db(transaction=True)
BASE = "/api/v1/broker/endorsements/"

GOOD = csv_text(
    "1001,Self,Asha Rao,17/05/1985,F,500000,,ABCDE1234F",
    "1001,Spouse,Ravi Rao,02-Feb-1983,M,500000,,",
    "1001,SON-II,Kid Rao,01/01/2015,M,500000,,",
    "1002,Self,Meera Nair,1990-03-03,F,\"₹ 3,00,000\",,PQRST5678K",
    "1003,Self,Raj Shah,1980-07-07,M,400000,,",
    "1003,Daughter,Tina Shah,2012-05-05,F,400000,,",
)


def states(job):
    return [a.to_state for a in AuditTransition.objects.filter(job=job)]


def job_from(resp):
    return EndorsementJob.objects.get(pk=resp.json()["job_id"])


def test_clean_file_flows_to_validation_passed(world):
    r = upload(client_for(), GOOD, effective_date="2030-04-01")
    assert r.status_code == 202, r.content
    body = r.json()
    assert body["progress_url"].endswith(f"{body['job_id']}/progress/")
    job = job_from(r)
    assert job.status == "VALIDATION_PASSED" and job.failed_rows == 0
    assert (job.total_rows, job.chunk_count, job.chunks_completed) == (6, 1, 1)
    assert states(job) == ["FILE_STREAMED", "VALIDATION_QUEUED", "VALIDATION_IN_PROGRESS", "VALIDATION_PASSED"]
    assert job.actor_user_id and job.file_sha256 and job.source_filename == "endorse.csv"
    assert StagedRow.objects.filter(job_id=job.id).count() == 6
    assert ValidationLog.objects.filter(job_id=job.id, severity="ERROR").count() == 0


def test_messy_cells_are_normalised_into_staging(world):
    job = job_from(upload(client_for(), GOOD, effective_date="2030-04-01"))
    rows = {(s.normalised_payload["employee_code"], s.normalised_payload["relation_code"]): s.normalised_payload
            for s in StagedRow.objects.filter(job_id=job.id)}
    assert rows[("1001", "SON_II")]["dob"] == "2015-01-01"                  # "SON-II" and 01/01/2015
    assert rows[("1001", "SPOUSE")]["dob"] == "1983-02-02"                  # 02-Feb-1983
    assert rows[("1002", "SELF")]["sum_insured"] == "300000.00"             # "₹ 3,00,000"
    assert rows[("1001", "SELF")]["effective_date"] == "2030-04-01"         # job-level default applied
    assert rows[("1001", "SELF")]["gender"] == "F" and rows[("1001", "SELF")]["first_name"] == "Asha"


def test_bad_rows_produce_a_precise_error_report_and_partial_status(world):
    text = csv_text(
        "2001,Self,Good Person,1990-01-01,M,500000,,ABCDE1234F",
        "2002,Self,Bad Pan,1990-01-01,M,500000,,SHORT",
        "2003,Self,Future Baby,2031-01-01,M,500000,,",
        "2004,Self,,1990-01-01,M,500000,,",
        "2005,Self,Odd Relation,1990-01-01,M,500000,,",
        "2005,Cousin,Who Dis,1990-01-01,M,500000,,",
        "2006,Self,Dup One,1990-01-01,M,500000,,",
        "2006,Self,Dup Two,1990-01-01,M,500000,,",
        "2007,Self,No SI,1990-01-01,M,,,",
    )
    job = job_from(upload(client_for(), text, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PARTIAL"
    assert job.total_rows == 9
    errs = client_for().get(f"{BASE}{job.id}/errors/?severity=ERROR").json()
    found = {(e["line"], e["rule"]) for e in errs["results"]}
    assert (3, "INVALID_PAN") in found
    assert (4, "DOB_IN_FUTURE") in found
    assert (5, "REQUIRED_FIELD") in found
    assert (7, "INVALID_RELATION") in found
    assert (9, "DUPLICATE_ROW_IN_FILE") in found
    assert (10, "REQUIRED_FIELD") in found
    assert not any(line == 2 for line, _ in found)                          # the good row is clean
    assert job.error_summary["errors_by_rule"]["REQUIRED_FIELD"] == 2
    assert job.failed_rows == 6                                              # distinct failing rows (2,3,4,6,8,9), not issue count


def test_a_file_where_every_row_fails_is_a_terminal_failure(world):
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,,,", "2,Self,B,1990-01-01,M,,,"),
                          effective_date="2030-04-01"))
    assert job.status == "VALIDATION_FAILED" and job.is_terminal and job.failed_rows == 2


def test_family_rules_come_from_policy_service_and_point_at_the_right_row(world):
    text = csv_text(
        "3001,Self,Dad,1980-01-01,M,500000,,",
        "3001,Son,Kid One,2012-01-01,M,500000,,",
        "3001,Daughter,Kid Two,2014-01-01,F,500000,,",
        "3001,Son-II,Kid Three,2016-01-01,M,500000,,",       # third child: max_children = 2
        "3002,Self,Other Dad,1980-01-01,M,500000,,",
        "3002,Father,Grandpa,1950-01-01,M,500000,,",         # FATHER not allowed by the construct
    )
    job = job_from(upload(client_for(), text, effective_date="2030-04-01"))
    errs = ValidationLog.objects.filter(job_id=job.id, severity="ERROR")
    by_rule = {(e.source_line, e.rule_code) for e in errs}
    assert (7, "RELATION_NOT_ALLOWED") in by_rule                           # exactly the FATHER row
    assert any(rule == "MAX_CHILDREN_EXCEEDED" and line == 2 for line, rule in by_rule)   # family-wide -> first row of 3001
    assert job.status == "VALIDATION_PARTIAL"


def test_family_validation_is_skipped_for_policies_without_a_construct(world):
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,500000,,", "1,Father,B,1950-01-01,M,500000,,"),
                          policy_id=101, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PASSED"
    assert "validate_families" not in world.calls


def test_one_policy_call_per_chunk_not_per_employee(world, settings):
    rows = [f"{i},Self,P{i},1990-01-01,M,500000,," for i in range(1, 41)]
    job = job_from(upload(client_for(), csv_text(*rows), effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PASSED"
    assert world.calls.count("validate_families") == job.chunk_count == 1


def test_existing_roster_drives_add_remove_update_rules(world):
    job0 = EndorsementJob.objects.create(broker_id=BROKER_A, employer_id=EMP_A, policy_id=100, source_type="CSV_UPLOAD")
    for code, rel, pan in (("5001", "SELF", "ABCDE1234F"), ("5001", "SPOUSE", None), ("5002", "SELF", "ZZZZZ9999Z")):
        EndorsementMember.objects.create(job=job0, policy_id=100, broker_id=BROKER_A, employer_id=EMP_A, employee_code=code,
                                         relation_code=rel, first_name="X", date_of_birth="1985-01-01", sum_insured=1,
                                         effective_date="2030-04-01", source_type="CSV_UPLOAD", pan=pan)
    add = job_from(upload(client_for(), csv_text("5001,Self,Again,1985-01-01,M,1,,", "5003,Self,PanClash,1990-01-01,M,1,,ZZZZZ9999Z"),
                          effective_date="2030-04-01"))
    rules = {(e.source_line, e.rule_code) for e in ValidationLog.objects.filter(job_id=add.id)}
    assert (2, "MEMBER_ALREADY_EXISTS") in rules and (3, "DUPLICATE_PAN_EXISTING") in rules

    rem = job_from(upload(client_for(), csv_text("5002,Self,,,,,,", "5009,Self,,,,,,", "5001,Self,,,,,,", header="Emp Code,Relationship,Name,DOB,Gender,SI,Effective Date,PAN"),
                          op="REMOVE", effective_date="2030-05-01"))
    rules = {(e.source_line, e.rule_code, e.severity) for e in ValidationLog.objects.filter(job_id=rem.id)}
    assert (3, "MEMBER_NOT_FOUND", "ERROR") in rules
    assert (4, "DEPENDENTS_REMAIN_ACTIVE", "WARN") in rules                 # 5001 removed but spouse stays: a warning only
    assert rem.status == "VALIDATION_PARTIAL"

    upd = job_from(upload(client_for(), csv_text("5002,Self,,,,,,", header="Emp Code,Relationship,Name,DOB,Gender,SI,Effective Date,PAN"),
                          op="UPDATE"))
    assert {e.rule_code for e in ValidationLog.objects.filter(job_id=upd.id)} == {"NOTHING_TO_UPDATE"}


def test_employee_families_are_never_split_across_chunks(world, settings):
    settings.CHUNK_TARGET_ROWS = 3
    lines = []
    for e in range(1, 7):                                                    # 6 employees, 3 rows each
        lines += [f"{e},Self,E{e},1980-01-01,M,1,,", f"{e},Spouse,S{e},1982-01-01,F,1,,", f"{e},Son,K{e},2012-01-01,M,1,,"]
    job = job_from(upload(client_for(), csv_text(*lines), effective_date="2030-04-01"))
    assert job.chunk_count == 6 and job.total_rows == 18 and job.status == "VALIDATION_PASSED"
    chunk_of = {}
    for s in StagedRow.objects.filter(job_id=job.id):
        chunk_of.setdefault(s.normalised_payload["employee_code"], set()).add(s.chunk_index)
    assert all(len(c) == 1 for c in chunk_of.values())


def test_a_large_family_extends_its_chunk_instead_of_splitting(world, settings):
    settings.CHUNK_TARGET_ROWS = 2
    lines = [f"9,Self,E,1980-01-01,M,1,,"] + [f"9,Son,K{i},2012-01-01,M,1,," for i in range(4)] + ["10,Self,F,1980-01-01,M,1,,"]
    job = job_from(upload(client_for(), csv_text(*lines), effective_date="2030-04-01"))
    sizes = sorted(ChunkState.objects.filter(job_id=job.id).values_list("row_count", flat=True))
    assert sizes == [1, 5]


def test_rows_for_one_employee_must_be_adjacent(world):
    text = csv_text("1,Self,A,1980-01-01,M,1,,", "2,Self,B,1980-01-01,M,1,,", "1,Spouse,C,1982-01-01,F,1,,")
    job = job_from(upload(client_for(), text, effective_date="2030-04-01"))
    assert (4, "EMPLOYEE_ROWS_NOT_CONTIGUOUS") in {(e.source_line, e.rule_code) for e in ValidationLog.objects.filter(job_id=job.id)}


# ── upload guards ────────────────────────────────────────────────────────────────────────────────────────────
def test_idempotency_key_replays_without_creating_a_second_job(world):
    c = client_for()
    first = upload(c, GOOD, idem="abc-1", effective_date="2030-04-01")
    again = upload(c, GOOD, idem="abc-1", effective_date="2030-04-01")
    assert first.status_code == 202 and again.status_code == 200
    assert again.json()["job_id"] == first.json()["job_id"] and again.json()["idempotent_replay"] is True
    assert EndorsementJob.objects.count() == 1
    clash = upload(c, GOOD + "9,Self,Z,1990-01-01,M,1,,\n", idem="abc-1", effective_date="2030-04-01")
    assert clash.status_code == 409
    other_broker = upload(client_for("BROKER_ADMIN", BROKER_B), GOOD, policy_id=200, idem="abc-1", effective_date="2030-04-01")
    assert other_broker.status_code == 202                                   # keys are scoped per broker


def test_uploaded_file_is_removed_from_the_spool_after_ingest(world, settings):
    import os
    upload(client_for(), GOOD, effective_date="2030-04-01")
    assert os.listdir(settings.UPLOAD_SPOOL_DIR) == []


@pytest.mark.parametrize("policy_id,code", [(102, 422), (999, 404), (200, 404), (103, 202)])
def test_policy_must_exist_be_live_and_belong_to_the_caller(world, policy_id, code):
    # 103 is live for the same broker but another employer: a BROKER user may act on any of its employers
    assert upload(client_for(), GOOD, policy_id=policy_id, effective_date="2030-04-01").status_code == code


def test_employer_user_cannot_use_another_employers_policy(world):
    c = client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A)
    assert upload(c, GOOD, policy_id=103, prefix="/api/v1/employer/endorsements/", effective_date="2030-04-01").status_code == 404
    ok = upload(c, GOOD, policy_id=100, prefix="/api/v1/employer/endorsements/", effective_date="2030-04-01")
    assert ok.status_code == 202 and job_from(ok).employer_id == EMP_A


def test_read_only_roles_cannot_upload(world):
    assert upload(client_for("BROKER_NON_ADMIN"), GOOD, effective_date="2030-04-01").status_code == 403


@pytest.mark.parametrize("kwargs,code", [
    ({"op": "COMBINE"}, 400), ({"op": "WHATEVER"}, 400), ({"filename": "x.xlsx"}, 400), ({"effective_date": "31-12-2030"}, 400),
])
def test_bad_request_shapes_are_400(world, kwargs, code):
    assert upload(client_for(), GOOD, **kwargs).status_code == code


def test_empty_oversized_and_unmappable_files_are_rejected_up_front(world, settings):
    assert upload(client_for(), "").status_code == 400
    settings.MAX_UPLOAD_BYTES = 50
    assert upload(client_for(), GOOD).status_code == 413
    settings.MAX_UPLOAD_BYTES = 10_000_000
    r = upload(client_for(), "colA,colB\n1,2\n", effective_date="2030-04-01")
    assert r.status_code == 422 and r.json()["missing_required"]
    assert EndorsementJob.objects.count() == 0                               # nothing half-created


def test_header_only_file_becomes_a_failed_job_with_a_reason(world):
    job = job_from(upload(client_for(), HEADER_ONLY, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_FAILED" and "no data rows" in job.error_summary["ingest_error"]


HEADER_ONLY = csv_text()


def test_row_limit_fails_the_job_and_cleans_staging(world, settings):
    settings.MAX_UPLOAD_ROWS = 2
    job = job_from(upload(client_for(), GOOD, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_FAILED" and "more than 2 rows" in job.error_summary["ingest_error"]
    assert StagedRow.objects.filter(job_id=job.id).count() == 0


def test_windows_1252_exports_are_read(world):
    from django.core.files.uploadedfile import SimpleUploadedFile
    raw = csv_text("1,Self,José Álvarez,1990-01-01,M,500000,,").encode("cp1252")
    r = client_for().post(BASE + "csv/upload/", {"file": SimpleUploadedFile("x.csv", raw), "op_type": "ADD", "policy_id": 101,
                                                 "effective_date": "2030-04-01"}, format="multipart")
    job = job_from(r)
    assert job.status == "VALIDATION_PASSED"
    assert StagedRow.objects.get(job_id=job.id).normalised_payload["first_name"] == "José"


def test_semicolon_delimited_files_are_read(world):
    job = job_from(upload(client_for(), "Emp Code;Relationship;Name;DOB;Sum Insured\n1;Self;Asha Rao;1990-01-01;500000\n",
                          policy_id=101, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PASSED"


# ── mappings ─────────────────────────────────────────────────────────────────────────────────────────────────
def test_preview_suggests_a_mapping_and_flags_gaps(world):
    from django.core.files.uploadedfile import SimpleUploadedFile
    c = client_for()
    r = c.post(BASE + "csv/preview/", {"file": SimpleUploadedFile("p.csv", GOOD.encode()), "op_type": "ADD"}, format="multipart")
    assert r.status_code == 200 and r.json()["auto_confirmable"] is True and r.json()["missing_required"] == []
    r = c.post(BASE + "csv/preview/", {"file": SimpleUploadedFile("p.csv", b"Emp Code,Remarks\n1,hi\n"), "op_type": "ADD"}, format="multipart")
    body = r.json()
    assert body["auto_confirmable"] is False and "relation" in body["missing_required"] and body["unmapped_headers"] == ["Remarks"]


def test_saved_mapping_is_reusable_with_a_date_format_hint_and_is_tenant_scoped(world):
    c = client_for()
    cm = {"ID": "employee_code", "Rel": "relation", "Who": "full_name", "Born": "dob", "Cover": "sum_insured"}
    m = c.post(BASE + "csv/mappings/", {"source_name": "Acme HRMS", "column_map": cm, "options": {"dob_format": "%m/%d/%Y"}}, format="json")
    assert m.status_code == 201
    mid = m.json()["id"]
    text = "ID,Rel,Who,Born,Cover\n1,Self,Asha Rao,05/17/1990,500000\n"
    job = job_from(upload(c, text, policy_id=101, mapping_id=mid, effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PASSED" and str(job.schema_mapping_id) == mid
    assert StagedRow.objects.get(job_id=job.id).normalised_payload["dob"] == "1990-05-17"      # month-first honoured
    assert [x["id"] for x in c.get(BASE + "csv/mappings/").json()] == [mid]
    assert client_for("BROKER_ADMIN", BROKER_B).get(BASE + "csv/mappings/").json() == []
    assert upload(client_for("BROKER_ADMIN", BROKER_B), text, policy_id=200, mapping_id=mid).status_code == 404
    upd = c.put(f"{BASE}csv/mappings/{mid}/", {"column_map": cm}, format="json")
    assert upd.json()["version"] == 2
    assert client_for("BROKER_ADMIN", BROKER_B).put(f"{BASE}csv/mappings/{mid}/", {"column_map": cm}, format="json").status_code == 404


@pytest.mark.parametrize("cm", [{}, {"A": "not_a_field"}, {"A": "dob", "B": "dob"}, "nope"])
def test_bad_mappings_are_rejected(world, cm):
    assert client_for().post(BASE + "csv/mappings/", {"source_name": "x", "column_map": cm}, format="json").status_code == 400


def test_mapping_that_misses_required_columns_is_422(world):
    c = client_for()
    mid = c.post(BASE + "csv/mappings/", {"source_name": "x", "column_map": {"ID": "employee_code"}}, format="json").json()["id"]
    r = upload(c, "ID,Foo\n1,2\n", mapping_id=mid)
    assert r.status_code == 422 and "relation" in r.json()["missing_required"]


# ── tenant isolation on the report ───────────────────────────────────────────────────────────────────────────
def test_error_report_is_tenant_scoped(world):
    job = job_from(upload(client_for(), csv_text("1,Self,,1990-01-01,M,1,,"), effective_date="2030-04-01"))
    assert client_for().get(f"{BASE}{job.id}/errors/").status_code == 200
    assert client_for("BROKER_ADMIN", BROKER_B).get(f"{BASE}{job.id}/errors/").status_code == 404
    assert client_for("EMPLOYER_ADMIN", BROKER_A, EMP_A + 5).get(f"/api/v1/employer/endorsements/{job.id}/errors/").status_code == 404


# ── resilience ───────────────────────────────────────────────────────────────────────────────────────────────
def test_policy_outage_is_retried_and_the_job_recovers(world):
    # upload's own policy lookup succeeds; the first two validation calls fail transiently
    from tests.fakes import FakePolicyClient
    c = client_for()
    FakePolicyClient.fail_next = []                                          # lookup in the request must pass
    orig = FakePolicyClient.validate_families

    attempts = {"n": 0}

    def flaky(self, *a, **k):
        attempts["n"] += 1
        if attempts["n"] <= 2:
            raise PolicyUnavailable("DEADLINE_EXCEEDED")
        return orig(self, *a, **k)

    FakePolicyClient.validate_families = flaky
    try:
        job = job_from(upload(c, GOOD, effective_date="2030-04-01"))
    finally:
        FakePolicyClient.validate_families = orig
    assert attempts["n"] == 3
    assert job.status == "VALIDATION_PASSED"
    assert ChunkState.objects.get(job_id=job.id).attempts == 3


def test_persistent_outage_parks_the_job_for_a_human(world):
    from tests.fakes import FakePolicyClient
    orig = FakePolicyClient.validate_families
    FakePolicyClient.validate_families = lambda self, *a, **k: (_ for _ in ()).throw(PolicyUnavailable("UNAVAILABLE"))
    try:
        job = job_from(upload(client_for(), GOOD, effective_date="2030-04-01"))
    finally:
        FakePolicyClient.validate_families = orig
    assert job.status == "MANUAL_INTERVENTION_REQUIRED"
    cs = ChunkState.objects.get(job_id=job.id)
    assert cs.status == "FAILED" and "unavailable" in cs.last_error


def test_a_rejected_service_credential_fails_fast_without_retrying(world):
    from tests.fakes import FakePolicyClient
    orig = FakePolicyClient.validate_families
    calls = {"n": 0}

    def denied(self, *a, **k):
        calls["n"] += 1
        raise PolicyAccessError("PERMISSION_DENIED")

    FakePolicyClient.validate_families = denied
    try:
        job = job_from(upload(client_for(), GOOD, effective_date="2030-04-01"))
    finally:
        FakePolicyClient.validate_families = orig
    assert calls["n"] == 1 and job.status == "MANUAL_INTERVENTION_REQUIRED"


def test_redelivered_chunk_tasks_do_not_double_count(world):
    from apps.validation.tasks import validate_chunk
    job = job_from(upload(client_for(), GOOD, effective_date="2030-04-01"))
    before = (job.chunks_completed, job.failed_rows, ValidationLog.objects.filter(job_id=job.id).count())
    assert validate_chunk.apply(args=[str(job.id), 0]).get() == "skipped"    # broker redelivers after the work finished
    job.refresh_from_db()
    assert (job.chunks_completed, job.failed_rows, ValidationLog.objects.filter(job_id=job.id).count()) == before
    assert job.status == "VALIDATION_PASSED" and AuditTransition.objects.filter(job=job, to_state="VALIDATION_PASSED").count() == 1


def test_a_crashed_worker_s_stale_chunk_can_be_reclaimed(world):
    from datetime import timedelta

    from django.utils import timezone

    from apps.validation.tasks import _claim
    job = job_from(upload(client_for(), GOOD, effective_date="2030-04-01"))
    ChunkState.objects.filter(job_id=job.id).update(status="RUNNING", started_at=timezone.now())
    assert _claim(job.id, 0) is False                                        # fresh RUNNING belongs to a live worker
    ChunkState.objects.filter(job_id=job.id).update(started_at=timezone.now() - timedelta(minutes=30))
    assert _claim(job.id, 0) is True                                         # stale: the worker died


def test_cancel_after_upload_stops_a_job_that_is_waiting_for_a_decision(world):
    job = job_from(upload(client_for(), csv_text("1,Self,A,1990-01-01,M,1,,", "2,Self,,1990-01-01,M,1,,"), effective_date="2030-04-01"))
    assert job.status == "VALIDATION_PARTIAL"
    assert client_for().post(f"{BASE}{job.id}/cancel/").json()["status"] == "CANCELLED_BY_HR"
