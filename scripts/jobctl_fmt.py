"""Formats jobctl.sh's HTTP responses (body, then the status code on the last line)."""
import json
import sys

raw = sys.stdin.read()
body, code = raw.rsplit("\n", 1)
try:
    d = json.loads(body)
except ValueError:
    d = body
if int(code) >= 300:
    print("HTTP", code, d.get("detail") or d.get("message") or d if isinstance(d, dict) else d)
    sys.exit(1)
if isinstance(d, list) and d and "runningNow" in d[0]:
    for j in d:
        run = "RUNNING " if j["runningNow"] else "        "
        print(f'{j["id"][:8]}  {j["status"]:11} {run}policy {j["policyId"]:<4} tpa {str(j["tpaId"]):<3} {j["syncedCount"]}/{j["membersExpected"]} synced  attempts {j["attempts"]}  {(j["lastError"] or "")[:70]}')
elif isinstance(d, list):
    for a in d:
        print(f'{a["at"][:19]}  {a["action"]:16} {a["outcome"]:15} {str(a["jobId"] or "")[:8]:9} {a["actor"]}  {a["detail"] or ""}')
else:
    print(json.dumps(d, indent=1, default=str))
