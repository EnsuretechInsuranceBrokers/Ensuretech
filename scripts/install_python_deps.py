"""Install a Python service's dependencies from its pyproject.toml (the services are applications, not installable packages).

    python ../../scripts/install_python_deps.py            # run inside services/<name>, virtual environment active
    python ../../scripts/install_python_deps.py --dev      # also the test tools (pytest ...)
    python ../../scripts/install_python_deps.py --devtools # also django-silk and Django Debug Toolbar (development only, DEV_TOOLS=true)
"""
import subprocess
import sys
import tomllib

project = tomllib.load(open("pyproject.toml", "rb"))["project"]
deps = list(project["dependencies"]) + (project.get("optional-dependencies", {}).get("dev", []) if "--dev" in sys.argv else []) + (project.get("optional-dependencies", {}).get("devtools", []) if "--devtools" in sys.argv else [])
subprocess.check_call([sys.executable, "-m", "pip", "install", *deps])
