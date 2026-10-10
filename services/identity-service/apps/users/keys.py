"""RS256 keypair loading. In dev, a keypair is generated on first run."""
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


def load_or_create_keypair(private_path: Path, public_path: Path) -> tuple[str, str]:
    if private_path.exists() and public_path.exists():
        return private_path.read_text(), public_path.read_text()
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    public_pem = key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode()
    private_path.parent.mkdir(parents=True, exist_ok=True)
    private_path.write_text(private_pem)
    private_path.chmod(0o600)
    public_path.write_text(public_pem)
    return private_pem, public_pem
