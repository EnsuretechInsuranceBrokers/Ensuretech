"""Where uploaded files wait between the HTTP request and the ingest worker.

The default spools to local disk, which is only valid while API and workers share a filesystem. The interface exists so
a MinIO/S3 store can replace it for multi-node deployments without touching the intake code.
"""
import hashlib
import os
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Protocol

from django.conf import settings
from django.utils.module_loading import import_string


class FileTooLarge(Exception):
    pass


@dataclass(frozen=True)
class StoredFile:
    key: str
    sha256: str
    size: int


class FileStore(Protocol):
    def save(self, stream, *, max_bytes: int) -> StoredFile: ...
    def open(self, key: str) -> BinaryIO: ...
    def delete(self, key: str) -> None: ...


def get_store() -> FileStore:
    return import_string(settings.FILE_STORE)()


class LocalFileStore:
    def __init__(self):
        self.root = Path(settings.UPLOAD_SPOOL_DIR)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        p = (self.root / key).resolve()
        if self.root.resolve() not in p.parents:        # keys come from us, but never trust a path join
            raise ValueError("bad key")
        return p

    def save(self, stream, *, max_bytes: int) -> StoredFile:
        key = f"{uuid.uuid4().hex}.csv"
        digest, size = hashlib.sha256(), 0
        path = self._path(key)
        try:
            with open(path, "wb") as out:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    size += len(block)
                    if size > max_bytes:
                        raise FileTooLarge(f"file exceeds {max_bytes} bytes")
                    digest.update(block)
                    out.write(block)
        except Exception:
            path.unlink(missing_ok=True)
            raise
        os.chmod(path, 0o600)
        return StoredFile(key, digest.hexdigest(), size)

    def open(self, key: str) -> BinaryIO:
        return open(self._path(key), "rb")

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)
