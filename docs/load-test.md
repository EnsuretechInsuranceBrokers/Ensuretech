# Load test: the new platform against the legacy system

`qa/load/loadtest.py` drives the platform through the BFF exactly as the portal does and times it. It uses only the Python standard library.

```bash
scripts/dev-up.sh --bench --no-kafka --with-sheet      # no Telescope, no query log, BFF rate limits raised, so the numbers are the software's
qa/load/loadtest.py endorse --rows 1000                 # one 1000-row sheet, from upload to ENDORSED
qa/load/loadtest.py endorse --rows 1000 --uploads 10 --parallel 10     # ten sheets at once
qa/load/loadtest.py reads --users 20 --seconds 20       # 20 users on the list endpoints: p50 / p95 / p99, errors
qa/load/loadtest.py gen --rows 1000 --out sheet.csv     # just the sheet, to upload into the legacy system by hand
```
Point it at any environment with `--base https://host/api/v1 --email … --password …` (or `ENSURETECH_BASE`, `ENSURETECH_EMAIL`, `ENSURETECH_PASSWORD`). `--token` skips sign-in (for example a break-glass token with `--base` pointing straight at one core service, to time it without the BFF). `--policy-id` defaults to 2 so the sample policy 1 stays small for the other QA scripts. Results are saved under `qa/load/results/`.

The sheet is the standard endorsement sheet (`Emp Code, Relationship, Member Name, DOB, Gender, Sum Insured, Effective Date, PAN`): each employee with a spouse and a child (3 rows), exactly the requested number of rows, employee codes taken from the clock so they are new every run (the engine correctly refuses a member who is already covered). The deposit must cover the premium or the job waits at `CD_PARTIAL_SUFFICIENT`; top it up first (locally `scripts/breakglass.sh call … POST billing /api/v1/broker/cd/accounts/POLICY/credits …`).

## Results on the build sandbox
**Read this first.** The sandbox is 4 vCPU (Xeon 2.1 GHz, no hyper-threading) and 15 GiB, and everything runs on it at once: PostgreSQL, Redis, nine JVM/Python services, three Celery worker groups, the load generator. Your production server is 8 vCPU (4 cores with hyper-threading, Xeon 8370C 2.8 GHz) and 15 GiB with about 5.7 GiB already in use. I cannot give the sandbox more CPU, so **these figures are not a head-to-head with production**; they show how the pipeline behaves on about half the compute. Re-run the same commands on a machine of the same size as production before drawing a conclusion. Local PostgreSQL and Redis are on the same box (no network hop), which flatters the platform a little; the sandbox's slower and fewer cores penalise it more.

Endorsement pipeline (1000 rows = 334 employees with dependants; real validation, pricing, deposit check, roster write; the job is verified in the database: 1000 staged, 1000 applied, 1000 members, 334 premium lines):

| Scenario | Result |
|---|---|
| 1 sheet × 1000 rows (10 repeats, one at a time) | **1.0 to 1.4 s** from upload to ENDORSED (median 1.03 s); the upload itself is accepted in about 0.1 s |
| 10 sheets × 1000 rows at the same time | all 10 ENDORSED in 6.0 s wall (1,660 rows/s); each sheet 1.9 to 6.0 s |
| 1 sheet × 10,000 rows | 7.4 s (1,350 rows/s) |
| 1 sheet × 25,000 rows | 19.1 s (1,310 rows/s), so the time grows in proportion to the rows |

Read APIs through the BFF (list endpoints, 20 simulated users): about 27 requests/s, p50 646 ms, p95 796 ms. That number is **not good**, and the investigation says why:

| Same call, `GET /broker/policies?size=20`, 20 users | requests/s | p50 | p95 |
|---|---|---|---|
| straight to policy-service (break-glass token, no BFF) | 319 | 5.8 ms | 26 ms |
| through the BFF (PHP built-in development server, 4 workers) | about 50 | about 340 ms | about 390 ms |
| one request, no concurrency, through the BFF | | 24 ms | |

The core service is fast; the BFF as run here (`php artisan serve`, the development server) saturates at about 50 requests a second. Turning on OPcache or using Redis for the cache did not change it (tried). A production deployment would run PHP-FPM behind nginx, or Laravel Octane, and that has not been measured. Until it is, **do not quote a read-throughput comparison**. The honest claim today is about bulk endorsements; the read path needs a measurement on the real web stack.

Other findings from these runs:
- `GET …/offline-template` takes time proportional to the policy's roster (18 s with 113,000 members on one policy, 0.9 s with a handful); it pages through every member. Worth fixing before a policy with a very large roster uses it.
- The BFF's rate limits (20 uploads a minute, 240 requests a minute per user, 600 per broker) correctly throttle a load test; `--bench` raises them for the test only.

## Comparing fairly with legacy
1. **Same hardware class.** Run this stack on a machine like production (8 vCPU, 15 GiB, nothing else busy), or compare only when the legacy server is idle. Record `lscpu`, `free -h` and what else was running.
2. **Same sheet.** `qa/load/loadtest.py gen --rows 1000 --out sheet.csv`, and give the legacy system the same file, same policy shape (same relationships, same pro-rata and deposit settings), same effective date.
3. **Same stopwatch.** From pressing upload to the endorsement showing as done (`ENDORSED` here). Do not stop at "upload accepted" in either system. Take at least 3 runs and use the median; note the time of day.
4. **Cold and warm.** Do one throwaway run first (JVM warm-up, caches), then measure.
5. **Concurrency.** Legacy: submit 3 or 5 sheets at the same moment from different browser sessions, note the total time. Here: `--uploads 5 --parallel 5`.
6. Fill in:

| Scenario | Legacy (production, measured by you) | New (same-size server) | New (build sandbox, 4 vCPU) |
|---|---|---|---|
| 1 × 1000 rows, upload to done | _ s | _ s | 1.03 s |
| 5 × 1000 rows at once, total | _ s | _ s | not measured at 5 (8 at once: 4.6 s; 10 at once: 6.0 s) |
| 1 × 10,000 rows | _ s | _ s | 7.4 s |

The new platform's time includes real validation, pricing and the deposit check. Confirm the legacy time includes the same work, or the comparison flatters one side.
