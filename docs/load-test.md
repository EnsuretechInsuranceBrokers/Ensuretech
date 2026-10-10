# Load test: the new platform against the legacy system

`qa/load/loadtest.py` drives the platform through the BFF exactly as the portal does and times it. It uses only the Python standard library.

```bash
scripts/dev-up.sh --bench --no-kafka --with-sheet --skip bff   # no Telescope, no query log, rate limits raised: the numbers are the software's
scripts/bff-fpm.sh --bench                              # the web tier as a real deployment runs it: nginx + PHP-FPM on :8090 (own terminal)
#   (leave out --skip bff and bff-fpm.sh to use the development server instead)
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

Read APIs through the BFF (20 simulated users, same sandbox). The first measurement, on PHP's built-in development server, was poor, and it was fixed:

| `GET /broker/policies?size=20` and the 3-endpoint mix, 20 users | requests/s | p50 | p95 | errors |
|---|---|---|---|---|
| policy-service alone, no BFF (the ceiling) | 319 | 5.8 ms | 26 ms | 0 |
| BFF on `php artisan serve` (before) | 50 | 340 ms | 390 ms | 0 |
| BFF on nginx + PHP-FPM, OPcache + JIT, 12 workers | 120 to 150 | 75 to 108 ms | 150 to 200 ms | 0 |
| + upstreams addressed as `127.0.0.1` (not `localhost`, which tries IPv6 first) | 160 to 170 | 61 to 70 ms | 115 to 135 ms | 0 |
| + Laravel config and route cache | 212 | 38 ms | 75 to 86 ms | 0 |
| + optimised Composer autoloader (final) | **197 to 225** | **35 to 47 ms** | **64 to 90 ms** | 0 |
| the original 3-endpoint mix (policies, employers, endorsements): before | 27 | 646 ms | 796 ms | 0 |
| the same mix, final (after the engine fix below) | **167 (6×)** | **61 ms** | 130 ms | 0 |

What made the difference, in order of effect: FPM with a pool of workers instead of the single-purpose development server; Laravel's config and route cache (`php artisan config:cache route:cache`, done by `scripts/bff-fpm.sh`); addressing upstreams by IP; the optimised autoloader (`composer dump-autoload -o`). At this point PHP is CPU-bound on the sandbox's 4 shared cores (about 12 ms of CPU a request), so more cores raise the ceiling roughly in proportion; 8 vCPU should do clearly better than these figures.

Run it this way: `scripts/bff-fpm.sh` (add `--bench` for load tests). It needs `php-fpm` and `nginx` installed (`apt install php8.3-fpm nginx`), writes its own configuration under `.dev/fpm`, builds the Laravel caches at start and removes them on exit. For a real deployment use the same settings in your own nginx and php-fpm pool: `pm = static` with 2 to 4 workers per core, `opcache.validate_timestamps=0` (restart php-fpm on deploy), `config:cache` and `route:cache` as a deploy step, **no** `fastcgi_keep_conn` or upstream keepalive to a pool this small (kept-alive connections pin FPM workers and the site stalls under load; that happened here, 30-second hangs), and Redis instead of the file cache when more than one BFF host runs.

The load test also found a real bug, now fixed. Under concurrency about 1 in 8 calls to the engine's endorsement list answered 500: `FATAL: remaining connection slots are reserved`. The engine runs under ASGI, where each request gets its own short-lived thread, and `CONN_MAX_AGE=60` left one open database connection per thread that was never reused, until PostgreSQL's 100 connections were gone (the five Spring services hold 50 of them). The engine now uses Django's connection pool (`DB_POOL_MAX`, default 8 per process; `DB_POOL=false` switches it off). After the change: 0 errors, and the engine holds 8 connections.

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
