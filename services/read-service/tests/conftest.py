import datetime as dt
import json
import tempfile
import uuid
from pathlib import Path

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from rest_framework.test import APIClient

_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUB = tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False)
_PUB.write(_KEY.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode())
_PUB.close()
ISSUER = "ensuretech-identity"
SAMPLES = Path(__file__).resolve().parents[2] / ".." / "shared" / "contracts" / "events"
BROKER_A, BROKER_B, EMP_A, EMP_A2, EMP_B = 1, 2, 10, 11, 20


def sample(name: str) -> dict:
    return json.loads((SAMPLES / f"{name}.sample.json").read_text())


def make_token(role="BROKER_ADMIN", broker_id=BROKER_A, employer_id=None, **kw):
    claims = {"iss": ISSUER, "token_type": kw.get("token_type", "access"), "user_id": str(uuid.uuid4()), "role": role,
              "exp": dt.datetime.now(dt.UTC) + dt.timedelta(seconds=600)}
    if broker_id is not None:
        claims["broker_id"] = broker_id
    if employer_id is not None:
        claims["employer_id"] = employer_id
    return jwt.encode(claims, _KEY, algorithm="RS256")


def client_for(role="BROKER_ADMIN", broker_id=BROKER_A, employer_id=None):
    c = APIClient()
    c.credentials(HTTP_AUTHORIZATION=f"Bearer {make_token(role, broker_id, employer_id)}")
    return c


@pytest.fixture(autouse=True)
def mongo_db(settings):
    """A throwaway database per test on a real MongoDB-wire server (MONGO_URI), dropped afterwards."""
    from apps.core import mongo
    from django.core.cache import cache
    settings.JWT_PUBLIC_KEY_PATH = Path(_PUB.name)
    settings.JWT_ISSUER = ISSUER
    settings.MONGO_URI = __import__("os").environ.get("TEST_MONGO_URI", "mongodb://127.0.0.1:27018")
    settings.MONGO_DB = f"readtest_{uuid.uuid4().hex[:12]}"
    settings.ANSWERER = "extractive"
    mongo.reset_client()
    cache.clear()
    mongo.ensure_indexes()
    yield mongo.system_db()
    mongo._client().drop_database(settings.MONGO_DB)
    mongo.reset_client()


@pytest.fixture
def db(mongo_db):
    return mongo_db
