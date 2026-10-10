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
BROKER_A, BROKER_B, EMP_A, EMP_A2, EMP_B = 1, 2, 10, 11, 20



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
def _settings(settings):
    settings.JWT_PUBLIC_KEY_PATH = Path(_PUB.name)
    settings.JWT_ISSUER = ISSUER
    settings.MAIL_BACKOFF_BASE_SECONDS = 60
    settings.MAIL_MAX_ATTEMPTS = 3


class SmtpServer:
    """A real SMTP server on a random port. `reply` can force a response code for RCPT to exercise failure handling."""

    def __init__(self):
        from aiosmtpd.controller import Controller
        outer = self
        self.messages, self.rcpt_reply, self.data_reply = [], None, None

        class H:
            async def handle_RCPT(self, server, session, envelope, address, rcpt_options):
                if outer.rcpt_reply:
                    return outer.rcpt_reply
                envelope.rcpt_tos.append(address)
                return "250 OK"

            async def handle_DATA(self, server, session, envelope):
                if outer.data_reply:
                    return outer.data_reply
                outer.messages.append(envelope)
                return "250 Message accepted"

        import socket
        with socket.socket() as probe:               # aiosmtpd's readiness check needs a concrete port
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.controller = Controller(H(), hostname="127.0.0.1", port=self.port)
        self.controller.start()

    def stop(self):
        self.controller.stop()


@pytest.fixture
def smtp(settings):
    s = SmtpServer()
    settings.SMTP_HOST, settings.SMTP_PORT, settings.SMTP_STARTTLS, settings.SMTP_USER = "127.0.0.1", s.port, False, ""
    yield s
    s.stop()


SAMPLES = Path(__file__).resolve().parents[3] / "shared" / "contracts" / "events"


def sample(name: str) -> dict:
    return json.loads((SAMPLES / f"{name}.sample.json").read_text())
