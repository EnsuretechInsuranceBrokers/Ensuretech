import datetime as dt
import tempfile
import uuid
from pathlib import Path

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from rest_framework.test import APIClient

from apps.jobs.models import EndorsementJob

_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_OTHER = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUB = tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False)
_PUB.write(_KEY.public_key().public_bytes(serialization.Encoding.PEM,
                                          serialization.PublicFormat.SubjectPublicKeyInfo).decode())
_PUB.close()
ISSUER = "ensuretech-identity"

BROKER_A, BROKER_B, EMP_A, EMP_B = 1, 2, 10, 20


def make_token(role="BROKER_ADMIN", broker_id=BROKER_A, employer_id=None, *, token_type="access", issuer=ISSUER,
               key=None, ttl=600, algorithm="RS256", user_id=None):
    claims = {"iss": issuer, "token_type": token_type, "user_id": user_id or str(uuid.uuid4()), "role": role,
              "exp": dt.datetime.now(dt.UTC) + dt.timedelta(seconds=ttl), "iat": dt.datetime.now(dt.UTC)}
    if broker_id is not None:
        claims["broker_id"] = broker_id
    if employer_id is not None:
        claims["employer_id"] = employer_id
    return jwt.encode(claims, key or _KEY, algorithm=algorithm)


def other_key():
    return _OTHER


@pytest.fixture(autouse=True)
def _jwt_settings(settings):
    settings.JWT_PUBLIC_KEY_PATH = Path(_PUB.name)
    settings.JWT_ISSUER = ISSUER


def client_for(role="BROKER_ADMIN", broker_id=BROKER_A, employer_id=None, **kw):
    c = APIClient()
    c.credentials(HTTP_AUTHORIZATION=f"Bearer {make_token(role, broker_id, employer_id, **kw)}")
    return c


@pytest.fixture
def broker_client():
    return client_for("BROKER_ADMIN", BROKER_A)


@pytest.fixture
def make_job(db):
    def _make(broker_id=BROKER_A, employer_id=EMP_A, policy_id=100, **kw):
        kw.setdefault("source_type", "CSV_UPLOAD")
        return EndorsementJob.objects.create(broker_id=broker_id, employer_id=employer_id, policy_id=policy_id, **kw)
    return _make


# ── pipeline fixtures ────────────────────────────────────────────────────────────────────────────────────────
@pytest.fixture
def world(settings, tmp_path):
    """Fake policy-service, spool dir in tmp, pipeline stops after validation unless a test opts in."""
    from tests.fakes import FakePolicyClient
    FakePolicyClient.reset()
    settings.POLICY_CLIENT = "tests.fakes.FakePolicyClient"
    settings.UPLOAD_SPOOL_DIR = str(tmp_path / "spool")
    settings.PIPELINE_AUTO_CONTINUE = False
    FakePolicyClient.add_policy(BROKER_A, 100, EMP_A, construct={"allowed": {"SPOUSE", "SON", "DAUGHTER", "SON_II"}, "max_children": 2, "max_members": 5})
    FakePolicyClient.add_policy(BROKER_A, 101, EMP_A, policy_type="GPA")
    FakePolicyClient.add_policy(BROKER_A, 102, EMP_A, status="NOT_APPROVED")
    FakePolicyClient.add_policy(BROKER_A, 103, EMP_A + 1)            # same broker, a different employer
    FakePolicyClient.add_policy(BROKER_B, 200, EMP_B)
    yield FakePolicyClient
    FakePolicyClient.reset()
    from django.db import connection
    with connection.cursor() as cur:                      # committed outbox rows must not leak into other tests
        cur.execute("DELETE FROM outbox.outbox_events")


HEADER = "Emp Code,Relationship,Member Name,DOB,Gender,Sum Insured,Effective Date,PAN"


def csv_text(*rows, header=HEADER):
    return "\n".join([header, *rows]) + "\n"


def upload(client, text, *, op="ADD", policy_id=100, idem=None, mapping_id=None, effective_date=None,
           filename="endorse.csv", prefix="/api/v1/broker/endorsements/", **extra):
    from django.core.files.uploadedfile import SimpleUploadedFile
    data = {"file": SimpleUploadedFile(filename, text.encode(), content_type="text/csv"), "op_type": op,
            "policy_id": policy_id, **extra}
    if mapping_id:
        data["mapping_id"] = mapping_id
    if effective_date:
        data["effective_date"] = effective_date
    kw = {"HTTP_IDEMPOTENCY_KEY": idem} if idem else {}
    return client.post(prefix + "csv/upload/", data, format="multipart", **kw)
