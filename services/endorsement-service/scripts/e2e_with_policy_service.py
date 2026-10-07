"""End-to-end check of the endorsement engine against the REAL policy-service over gRPC, with real identity tokens.

Prerequisites (see the service READMEs): a running policy-service (REST :8082, gRPC :9090) with a LIVE GMC policy
id 1 for employer 10 / broker 1 (rate card flat 1000, EXACT_DAYS pro-rata 2030-04-01..2031-03-31, family construct
allowing SPOUSE/SON/DAUGHTER, max 5 members, max 2 children), a migrated UTF-8 database, `manage.py genproto`, and
tokens from identity-service in the environment:

    E2E_BROKER_TOKEN  BROKER_ADMIN broker 1      E2E_OTHER_TOKEN  BROKER_ADMIN broker 2
    E2E_SYSTEM_TOKEN  SYSTEM (service account)   E2E_HR_TOKEN     EMPLOYER_ADMIN broker 1 employer 10

Service credentials: either static SYSTEM tokens (POLICY_GRPC_TOKEN / BILLING_GRPC_TOKEN) or, preferred, the client-credentials flow:
IDENTITY_TOKEN_URL=http://localhost:8000/api/v1/auth/service-token/ SERVICE_CLIENT_ID=... SERVICE_CLIENT_SECRET=...
(create with `python manage.py service_account create <name>` in identity-service).

Run:  DATABASE_URL=... POLICY_GRPC_TARGET=localhost:9090 POLICY_GRPC_TOKEN=$E2E_SYSTEM_TOKEN \
      CD_CLIENT=tests.fakes.InMemoryCd CELERY_TASK_ALWAYS_EAGER=True python scripts/e2e_with_policy_service.py
Deposit check: by default an in-memory double. Set E2E_BILLING_URL (billing-service REST, e.g. http://localhost:8084) and
BILLING_GRPC_TARGET/BILLING_GRPC_TOKEN (SYSTEM token) to run it against the REAL billing-service instead; the policy's
deposit account must already exist (normally opened by the PolicyApproved event) and be funded by the script.
"""
import os
import sys
from datetime import date
from decimal import ROUND_HALF_UP, Decimal

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

import django  # noqa: E402

django.setup()
from django.conf import settings  # noqa: E402
from django.core.files.uploadedfile import SimpleUploadedFile  # noqa: E402
from django.core.management import call_command  # noqa: E402
from django.utils import timezone  # noqa: E402

RUN_START = timezone.now()
from rest_framework.test import APIClient  # noqa: E402

settings.ALLOWED_HOSTS = ["testserver"]
settings.PIPELINE_AUTO_CONTINUE = True
settings.UPLOAD_SPOOL_DIR = "/tmp/e2e-spool"
call_command("migrate", verbosity=0)

from apps.jobs.models import AuditTransition, EndorsementJob, EndorsementMember  # noqa: E402
from apps.validation.models import ValidationLog  # noqa: E402
from tests.fakes import InMemoryCd  # noqa: E402

REAL_BILLING = bool(os.environ.get("E2E_BILLING_URL"))
if REAL_BILLING:
    settings.CD_CLIENT = "apps.computation.cd.GrpcCdClient"
else:
    InMemoryCd.reset("1000000")
BASE = "/api/v1/broker/endorsements/"
HEADER = "Emp Code,Relationship,Member Name,DOB,Gender,Sum Insured,Effective Date"
CHECKS = []


def client(env):
    c = APIClient()
    c.credentials(HTTP_AUTHORIZATION=f"Bearer {os.environ[env]}")
    return c


def upload(c, rows, policy_id=1, prefix=BASE, effective="2030-10-01"):
    text = "\n".join([HEADER, *rows]) + "\n"
    return c.post(prefix + "csv/upload/", {"file": SimpleUploadedFile("e2e.csv", text.encode()), "op_type": "ADD",
                                           "policy_id": policy_id, "effective_date": effective}, format="multipart")


def check(label, cond, detail=""):
    CHECKS.append((label, bool(cond)))
    print(("PASS  " if cond else "FAIL  ") + label + (f"   [{detail}]" if detail and not cond else ""))


EndorsementMember.objects.all().delete() if False else None   # never wipe data implicitly; the run uses fresh employee codes
tag = date.today().strftime("%m%d") + str(EndorsementJob.objects.count())

# 1 ── a clean family goes all the way to the roster, priced by the real Java engine
r = upload(client("E2E_BROKER_TOKEN"), [f"{tag}01,Self,Asha Rao,17/05/1985,F,500000,,", f"{tag}01,Spouse,Ravi Rao,02-Feb-1983,M,500000,,",
                                        f"{tag}01,Son-I,Kid Rao,01/01/2015,M,500000,,"])
job = EndorsementJob.objects.get(pk=r.json()["job_id"])
factor = (Decimal(182) / Decimal(365)).quantize(Decimal("0.000001"))
expected = (Decimal(3000) * factor).quantize(Decimal("0.01"), ROUND_HALF_UP)
tax = (expected * Decimal("18") / 100).quantize(Decimal("0.01"), ROUND_HALF_UP)
check("clean family reaches ENDORSED", job.status == "ENDORSED", f"{job.status} {job.error_summary}")
check("premium = 3 x 1000 x exact-days pro-rata (182/365)", job.premium_delta == expected, f"{job.premium_delta} vs {expected}")
check("tax is 18% of the pro-rated premium", job.tax_amount == tax and job.total_premium == expected + tax)
check("three members are on the live roster", EndorsementMember.objects.filter(employee_code=f"{tag}01", is_active=True).count() == 3)
check("full audit trail recorded", AuditTransition.objects.filter(job=job).count() == 11)

# 2 ── the real family rules come back with the right member attributed
r = upload(client("E2E_BROKER_TOKEN"), [f"{tag}02,Self,Dad,1980-01-01,M,500000,,", f"{tag}02,Father,Grandpa,1950-01-01,M,500000,,",
                                        f"{tag}03,Self,Other,1980-01-01,M,500000,,", f"{tag}03,Son,A,2012-01-01,M,500000,,",
                                        f"{tag}03,Daughter,B,2014-01-01,F,500000,,", f"{tag}03,Son-II,C,2016-01-01,M,500000,,"])
job = EndorsementJob.objects.get(pk=r.json()["job_id"])
found = {(e.source_line, e.rule_code) for e in ValidationLog.objects.filter(job_id=job.id, severity="ERROR")}
check("job stops at VALIDATION_PARTIAL for human review", job.status == "VALIDATION_PARTIAL", job.status)
check("FATHER row (line 3) flagged RELATION_NOT_ALLOWED by policy-service", (3, "RELATION_NOT_ALLOWED") in found, str(found))
check("third child flagged MAX_CHILDREN_EXCEEDED on the family's first row (line 4)", (4, "MAX_CHILDREN_EXCEEDED") in found, str(found))
check("nothing from the bad families reached the roster before confirmation", EndorsementMember.objects.filter(employee_code__in=[f"{tag}02", f"{tag}03"]).count() == 0)
done = client("E2E_BROKER_TOKEN").post(f"{BASE}{job.id}/proceed/")
job.refresh_from_db()
check("after HR confirms, only the valid rows are endorsed", done.status_code == 202 and job.status == "ENDORSED"
      and EndorsementMember.objects.filter(employee_code=f"{tag}02").count() == 1, f"{job.status} {job.error_summary}")

# 3 ── tenancy across services
check("another broker cannot use broker 1's policy (404 via real gRPC NOT_FOUND)", upload(client("E2E_OTHER_TOKEN"), [f"{tag}09,Self,X,1990-01-01,M,1,,"]).status_code == 404)
hr = upload(client("E2E_HR_TOKEN"), [f"{tag}10,Self,Hr User,1990-01-01,M,500000,,"], prefix="/api/v1/employer/endorsements/")
check("an HR user can upload for their own employer's policy", hr.status_code == 202 and EndorsementJob.objects.get(pk=hr.json()["job_id"]).status == "ENDORSED")

# 4 ── a refused service credential is reported, not retried forever
from apps.core import service_auth  # noqa: E402

CLIENT_CREDENTIALS = service_auth.provider.configured()


def break_service_auth():
    """Make outgoing service credentials invalid; returns the undo. Client-credentials mode spoils the secret and drops the cache."""
    saved = (settings.POLICY_GRPC_TOKEN, settings.BILLING_GRPC_TOKEN, settings.SERVICE_CLIENT_SECRET)
    settings.POLICY_GRPC_TOKEN = settings.BILLING_GRPC_TOKEN = "not-a-token"
    settings.SERVICE_CLIENT_SECRET = "wrong-secret"
    service_auth.provider.invalidate()

    def undo():
        settings.POLICY_GRPC_TOKEN, settings.BILLING_GRPC_TOKEN, settings.SERVICE_CLIENT_SECRET = saved
        service_auth.provider.invalidate()
    return undo


undo = break_service_auth()
bad = upload(client("E2E_BROKER_TOKEN"), [f"{tag}11,Self,Y,1990-01-01,M,1,,"])
undo()
check("a rejected service token surfaces as 503, not a crash", bad.status_code == 503, f"{bad.status_code} {bad.content[:80]}")

# 5 ── the deposit, for real: reservations, no double-spending, top-up and retry (only against the real billing-service)
if REAL_BILLING:
    import json
    import urllib.request

    def billing(path, method="GET", body=None):
        req = urllib.request.Request(os.environ["E2E_BILLING_URL"] + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": f"Bearer {os.environ['E2E_BROKER_TOKEN']}", "Content-Type": "application/json"})
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read(), parse_float=Decimal)

    acct = lambda: billing("/api/v1/broker/cd/accounts/1")  # noqa: E731
    jobs_run = list(EndorsementJob.objects.filter(source_type="CSV_UPLOAD", status="ENDORSED", total_premium__gt=0)
                    .filter(created_at__gte=RUN_START).order_by("created_at"))
    a = acct()
    check("every endorsed job's total is held in billing's ledger (reserved == sum of totals)",
          a["reserved"] == sum(j.total_premium for j in jobs_run) and a["reserved"] > 0, f"{a['reserved']} vs {sum(j.total_premium for j in jobs_run)}")
    check("available = balance - reserved", a["available"] == a["balance"] - a["reserved"], str(a))

    # a job that needs more than what is left must NOT pass, even though the balance alone would cover it
    need_members = int(a["available"] / Decimal("588.38")) + 1                      # one more member than the free money covers
    rows = [f"{tag}2{i:02d},Self,Extra {i},1990-01-01,M,500000,," for i in range(need_members)]
    over = EndorsementJob.objects.get(pk=upload(client("E2E_BROKER_TOKEN"), rows).json()["job_id"])
    check("a job exceeding the unreserved money is blocked at the deposit check although the balance alone would cover it",
          over.status in ("CD_PARTIAL_SUFFICIENT", "CD_INSUFFICIENT") and a["balance"] >= over.total_premium > a["available"],
          f"{over.status} total={over.total_premium} avail={a['available']} balance={a['balance']}")
    check("it holds nothing and reached no roster", acct()["reserved"] == a["reserved"] and EndorsementMember.objects.filter(employee_code__startswith=f"{tag}2").count() == 0)

    billing("/api/v1/broker/cd/accounts/1/credits", "POST", {"amount": 5000, "reference": f"E2E-TOPUP-{tag}", "type": "DEPOSIT"})
    retried = client("E2E_BROKER_TOKEN").post(f"{BASE}{over.id}/retry/")
    over.refresh_from_db()
    check("after a top-up, retry passes the check and endorses", retried.status_code == 202 and over.status == "ENDORSED", f"{over.status}")
    b = acct()
    check("the retried job's total is now held too", b["reserved"] == a["reserved"] + over.total_premium, f"{b['reserved']} vs {a['reserved'] + over.total_premium}")

    # a deadline-bounded refusal: with a bad service token the check fails visibly instead of passing
    settings.CD_GRPC_DEADLINE_SECONDS = 0.5
    from apps.computation.tasks import MAX_CD_ATTEMPTS  # noqa: E402,F401
    # the policy lookup must still work, so spoil only billing's credential (cd.py gets a stand-in for service_auth)
    from types import SimpleNamespace  # noqa: E402
    from apps.computation import cd as cd_mod  # noqa: E402
    real_auth, saved_b = cd_mod.service_auth, settings.BILLING_GRPC_TOKEN
    cd_mod.service_auth = SimpleNamespace(bearer=lambda static: "Bearer not-a-token", provider=service_auth.provider,
                                          ServiceAuthError=service_auth.ServiceAuthError)
    denied = EndorsementJob.objects.get(pk=upload(client("E2E_BROKER_TOKEN"), [f"{tag}300,Self,Denied,1990-01-01,M,500000,,"]).json()["job_id"])
    cd_mod.service_auth, settings.BILLING_GRPC_TOKEN = real_auth, saved_b
    check("a refused billing credential fails the check visibly (CD_CHECK_FAILED), never silently passes",
          denied.status == "CD_CHECK_FAILED" and EndorsementMember.objects.filter(employee_code=f"{tag}300").count() == 0, denied.status)

failed = [l for l, ok in CHECKS if not ok]
print(f"\n{len(CHECKS) - len(failed)}/{len(CHECKS)} checks passed")
sys.exit(1 if failed else 0)
