#!/usr/bin/env python3
"""Load test for the endorsement pipeline and the read APIs, through the BFF like the portal does. Standard library only.

  qa/load/loadtest.py endorse --rows 1000                 upload one 1000-row sheet and time it from upload to ENDORSED
  qa/load/loadtest.py endorse --rows 1000 --uploads 4 --parallel 4     four sheets at once (concurrent employers' load)
  qa/load/loadtest.py reads --users 20 --seconds 20       N simulated users hammering the list endpoints; p50/p95/p99 and errors
  qa/load/loadtest.py gen --rows 1000 --out sheet.csv     only write the sheet (to upload by hand into the legacy system for comparison)

Point it anywhere with --base (default the local BFF) and --email/--password (or ENSURETECH_EMAIL / ENSURETECH_PASSWORD). Results are printed and saved under qa/load/results/.
What it measures for an upload: the time the BFF takes to accept the file, then the time until each status is first seen, until ENDORSED (or a failure), and rows per second end to end.
Compare like with like: the same row count, the same policy shape, workers started the way you will run them in production, and the legacy system timed from the moment the file is submitted
to the moment its endorsement shows as done. Local numbers are one laptop running everything (database, Redis, all services): they bound the software, not your hardware.
"""
import argparse
import csv
import json
import os
import random
import statistics
import string
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta

DONE = {"ENDORSED"}
HERE = os.path.dirname(os.path.abspath(__file__))


def letters(n):
    return "".join(random.choices(string.ascii_uppercase, k=n))


def make_sheet(rows, path, start_code=None, effective="", fail=0):
    """Employees with a spouse and one child each (3 rows), then single employees to reach exactly `rows`. Codes and PANs are fresh on every call."""
    code = start_code or random.randint(100000, 800000)
    out = []
    while len(out) < rows:
        code += 1
        pan = f"{letters(5)}{random.randint(1000, 9999)}{letters(1)}"
        name = f"Load Tester {code}"
        out.append([code, "Self", name, "17/05/1985", "F", 500000, effective, pan])
        if len(out) < rows:
            out.append([code, "Spouse", f"Load Spouse {code}", "02/02/1983", "M", 500000, effective, ""])
        if len(out) < rows:
            out.append([code, "SON", f"Load Child {code}", "01/01/2015", "M", 500000, effective, ""])
    out = out[:rows]
    if fail:                                       # spoil `fail` rows, spread evenly, cycling through three kinds of mistake that real sheets have
        step = max(1, rows // fail)
        for n, i in enumerate(range(0, rows, step)):
            if n >= fail:
                break
            kind = n % 3
            if kind == 0:
                out[i][3] = "99/99/9999"           # a date that does not exist
            elif kind == 1:
                out[i][2] = ""                     # no member name
            else:
                out[i][1] = "NEPHEW"               # a relationship the policy does not know
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Emp Code", "Relationship", "Member Name", "DOB", "Gender", "Sum Insured", "Effective Date", "PAN"])
        w.writerows(out)
    return path


class Api:
    def __init__(self, base, email, password):
        self.base = base.rstrip("/")
        self.token = None
        self.email, self.password = email, password

    def call(self, method, path, body=None, headers=None, raw=None, timeout=60, base=None):
        h = {"Accept": "application/json", **(headers or {})}
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if self.token and "Authorization" not in h:
            h["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request((base or self.base) + path, data=data, method=method, headers=h)
        t = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                payload = r.read()
                code = r.status
        except urllib.error.HTTPError as e:
            payload, code = e.read(), e.code
        except Exception as e:                                    # connection refused, timeout
            return 0, {"error": str(e)}, (time.perf_counter() - t) * 1000
        ms = (time.perf_counter() - t) * 1000
        try:
            return code, json.loads(payload or b"null"), ms
        except ValueError:
            return code, {"raw": payload[:200].decode("latin1")}, ms

    def login(self):
        for attempt in range(6):
            code, body, _ = self.call("POST", "/auth/login", {"email": self.email, "password": self.password})
            if code == 200:
                self.token = body["access_token"]
                return
            if code == 429:
                time.sleep(20)
                continue
            sys.exit(f"login failed ({code}): {body}")
        sys.exit("login kept being rate limited")

    def upload(self, path, policy_id, op_type, effective, correlation_id, idem):
        boundary = uuid.uuid4().hex
        parts = []
        for k, v in (("policy_id", policy_id), ("op_type", op_type), ("effective_date", effective)):
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
        with open(path, "rb") as f:
            blob = f.read()
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{os.path.basename(path)}"\r\nContent-Type: text/csv\r\n\r\n'.encode() + blob + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        return self.call("POST", "/broker/endorsements/csv/upload", raw=b"".join(parts), timeout=300,
                         headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "Idempotency-Key": idem, "X-Correlation-Id": correlation_id})


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))], 1)


def fresh_code_base(n):
    """Employee codes must be new on the policy (the engine refuses a member who is already covered, correctly), so they come from the clock, never from chance."""
    return (int(time.time()) - 1767225600) * 100000 + n * 20000


def run_one(args, n, effective, token):
    api = Api(args.base, args.email, args.password)
    api.token = token
    path = make_sheet(args.rows, os.path.join(args.workdir, f"load-{os.getpid()}-{n}.csv"), start_code=fresh_code_base(n), effective=args.sheet_effective_date, fail=args.fail)
    cid = f"load-{int(time.time())}-{n}-{uuid.uuid4().hex[:6]}"
    t0 = time.perf_counter()
    code, job, accept_ms = api.upload(path, args.policy_id, args.op_type, effective, cid, f"{cid}")
    res = {"n": n, "rows": args.rows, "correlation_id": cid, "http": code, "accept_ms": round(accept_ms, 1)}
    if code not in (200, 201, 202) or "id" not in job:
        res.update(status="UPLOAD_REFUSED", detail=job)
        return res
    jid, seen, last = job["id"], {}, None
    deadline = t0 + args.timeout
    while time.perf_counter() < deadline:
        c, j, _ = api.call("GET", f"/broker/endorsements/{jid}")
        if c == 200:
            st = j["status"]
            if st != last:
                seen[st] = round(time.perf_counter() - t0, 2)
                last = st
            if st == "VALIDATION_PARTIAL" and args.proceed_base and "PROCEEDED" not in seen:
                seen["PROCEEDED"] = round(time.perf_counter() - t0, 2)           # the person clicks "continue with the valid rows"
                api.call("POST", f"/broker/endorsements/{jid}/proceed/", base=args.proceed_base, headers={"Authorization": f"Bearer {args.proceed_token}"})
            waiting = st == "VALIDATION_PARTIAL" and not args.proceed_base
            if st in DONE or "FAILED" in st or waiting or st in ("CD_INSUFFICIENT", "CANCELLED"):
                res.update(total_rows=j.get("total_rows"), failed_rows=j.get("failed_rows"), chunks=j.get("chunk_count"), total_premium=j.get("total_premium"), cd_status=j.get("cd_status"))
                break
        time.sleep(args.poll)
    total = time.perf_counter() - t0
    if res.get("failed_rows"):                                              # what a person downloads as the error list: every error, all pages
        e0, errs, page = time.perf_counter(), 0, 1
        while True:
            c, j, _ = api.call("GET", f"/broker/endorsements/{jid}/errors?page={page}&page_size=500")
            if c != 200 or not j.get("items"):
                break
            errs += len(j["items"])
            if errs >= j.get("total", 0):
                break
            page += 1
        res.update(error_rows_fetched=errs, seconds_to_fetch_all_errors=round(time.perf_counter() - e0, 2))
    res.update(job_id=jid, status=last or "NO_STATUS", seconds_to_final=round(total, 2), first_seen_at_seconds=seen,
               rows_per_second=round(args.rows / total, 1) if last in DONE else None, failed=res.get("failed_rows"), timed_out=time.perf_counter() >= deadline)
    os.remove(path)
    return res


def cmd_endorse(args):
    effective = args.effective_date or (date.today() + timedelta(days=35)).isoformat()
    print(f"uploading {args.uploads} sheet(s) of {args.rows} rows to {args.base}, policy {args.policy_id}, effective {effective}, {args.parallel} at a time")
    login = Api(args.base, args.email, args.password)       # one sign-in, outside the timed part, like a person who is already in the portal
    if args.token:
        login.token = args.token
    else:
        login.login()
    started = time.perf_counter()
    with ThreadPoolExecutor(args.parallel) as ex:
        results = list(ex.map(lambda n: run_one(args, n, effective, login.token), range(1, args.uploads + 1)))
    wall = time.perf_counter() - started
    ok = [r for r in results if r["status"] in DONE]
    summary = {"kind": "endorse", "label": args.label, "base": args.base, "when": datetime.now().isoformat(timespec="seconds"), "rows_per_sheet": args.rows, "uploads": args.uploads, "parallel": args.parallel,
               "succeeded": len(ok), "wall_seconds": round(wall, 2), "total_rows_endorsed": len(ok) * args.rows, "overall_rows_per_second": round(len(ok) * args.rows / wall, 1) if wall else None,
               "seconds_to_final": {"min": min((r["seconds_to_final"] for r in ok), default=None), "median": round(statistics.median([r["seconds_to_final"] for r in ok]), 2) if ok else None,
                                    "max": max((r["seconds_to_final"] for r in ok), default=None)},
               "runs": results}
    return summary


def cmd_reads(args):
    paths = [p.strip() for p in args.paths.split(",")]
    lat, errors, lock, stop = {p: [] for p in paths}, {p: 0 for p in paths}, threading.Lock(), time.perf_counter() + args.seconds

    one = Api(args.base, args.email, args.password)
    if args.token:
        one.token = args.token
    else:
        one.login()                                               # sign-in is rate limited by design; the simulated users share one session, as tabs of one person would

    def user(i):
        api = Api(args.base, args.email, args.password)
        api.token = one.token
        while time.perf_counter() < stop:
            p = random.choice(paths)
            code, _, ms = api.call("GET", p, timeout=30)
            with lock:
                lat[p].append(ms)
                if code != 200:
                    errors[p] += 1
            time.sleep(args.think)
    print(f"{args.users} users on {len(paths)} endpoints for {args.seconds}s against {args.base}")
    t = time.perf_counter()
    with ThreadPoolExecutor(args.users) as ex:
        list(ex.map(user, range(args.users)))
    wall = time.perf_counter() - t
    per, every = {}, []
    for p in paths:
        v = lat[p]
        every += v
        per[p] = {"requests": len(v), "errors": errors[p], "p50_ms": pct(v, 50), "p95_ms": pct(v, 95), "p99_ms": pct(v, 99), "max_ms": round(max(v), 1) if v else None}
    return {"kind": "reads", "label": args.label, "base": args.base, "when": datetime.now().isoformat(timespec="seconds"), "users": args.users, "seconds": round(wall, 1), "requests": len(every),
            "requests_per_second": round(len(every) / wall, 1), "errors": sum(errors.values()), "p50_ms": pct(every, 50), "p95_ms": pct(every, 95), "p99_ms": pct(every, 99), "per_endpoint": per}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["gen", "endorse", "reads"])
    ap.add_argument("--base", default=os.environ.get("ENSURETECH_BASE", "http://127.0.0.1:8090/api/v1"))
    ap.add_argument("--email", default=os.environ.get("ENSURETECH_EMAIL", "meera.kapoor@northwind.example"))
    ap.add_argument("--password", default=os.environ.get("ENSURETECH_PASSWORD", "Str0ng-Pass-123!"))
    ap.add_argument("--token", default=os.environ.get("ENSURETECH_TOKEN", ""), help="use this bearer token instead of signing in (e.g. a break-glass token, with --base pointing straight at a core service, to time it without the BFF)")
    ap.add_argument("--fail", type=int, default=0, help="spoil this many rows of the sheet (a bad date, a missing name, an unknown relationship)")
    ap.add_argument("--proceed-base", default="", help="when validation leaves some rows failed, continue with the valid ones by calling the engine's proceed endpoint at this base (e.g. http://127.0.0.1:8083/api/v1 with --token a break-glass token): the BFF has no such route")
    ap.add_argument("--proceed-token", default=os.environ.get("ENSURETECH_PROCEED_TOKEN", ""), help="a break-glass token for a broker admin, used only for the proceed call")
    ap.add_argument("--label", default="local", help="a name for this run in the results file, e.g. 'local-4workers' or 'prod'")
    ap.add_argument("--rows", type=int, default=1000)
    ap.add_argument("--uploads", type=int, default=1)
    ap.add_argument("--parallel", type=int, default=1)
    ap.add_argument("--policy-id", type=int, default=int(os.environ.get("ENSURETECH_POLICY_ID", "2")))
    ap.add_argument("--op-type", default="ADD")
    ap.add_argument("--effective-date", default="")
    ap.add_argument("--sheet-effective-date", default="", help="value for the sheet's own Effective Date column (blank = use the form's)")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--poll", type=float, default=0.25)
    ap.add_argument("--workdir", default="/tmp")
    ap.add_argument("--out", default="")
    ap.add_argument("--users", type=int, default=20)
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--think", type=float, default=0.05, help="pause between a simulated user's requests")
    ap.add_argument("--paths", default="/broker/employers?size=20,/broker/policies?size=20,/broker/endorsements?size=20")
    args = ap.parse_args()
    if args.cmd == "gen":
        print(make_sheet(args.rows, args.out or "sheet.csv", effective=args.sheet_effective_date))
        return
    result = cmd_endorse(args) if args.cmd == "endorse" else cmd_reads(args)
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", f"{datetime.now():%Y%m%d-%H%M%S}-{args.cmd}-{args.label}.json")
    with open(out, "w") as f:
        json.dump(result, f, indent=2)
    print(json.dumps({k: v for k, v in result.items() if k != "runs"}, indent=2))
    for r in result.get("runs", []):
        print(f"  upload {r['n']}: {r['status']} in {r.get('seconds_to_final')} s (accepted in {r['accept_ms']} ms) {r.get('first_seen_at_seconds')}")
    print("saved", out)


if __name__ == "__main__":
    main()
