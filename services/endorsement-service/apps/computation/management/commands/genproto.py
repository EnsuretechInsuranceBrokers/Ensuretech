"""Generate Python gRPC stubs from the shared contracts into apps/computation/gen (not committed)."""
import re
import subprocess
import sys
from pathlib import Path

from django.core.management.base import BaseCommand

SERVICE_ROOT = Path(__file__).resolve().parents[4]
REPO_ROOT = SERVICE_ROOT.parents[1]
PROTO_DIR = REPO_ROOT / "shared" / "contracts" / "grpc"
OUT = SERVICE_ROOT / "apps" / "computation" / "gen"


def generate() -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "__init__.py").touch()
    protos = sorted(PROTO_DIR.glob("*.proto"))
    if not protos:
        raise RuntimeError(f"no .proto files in {PROTO_DIR}")
    subprocess.run([sys.executable, "-m", "grpc_tools.protoc", f"-I{PROTO_DIR}", f"--python_out={OUT}",
                    f"--grpc_python_out={OUT}", *map(str, protos)], check=True)
    for f in OUT.glob("*_pb2_grpc.py"):          # protoc emits absolute imports; make them package-relative
        f.write_text(re.sub(r"^import (\w+_pb2) as", r"from . import \1 as", f.read_text(), flags=re.M))
    return OUT


class Command(BaseCommand):
    help = "Generate gRPC stubs from shared/contracts/grpc"

    def handle(self, *args, **opts):
        self.stdout.write(f"generated into {generate()}")
