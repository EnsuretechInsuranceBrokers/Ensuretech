# QA handbook: manual sanity, automation, and debugging by hand

Everything runs on your machine. Setup is `run.html` (§0 gives the order; §3a is the one-command launcher). This page is what you do once it is running.

## 1. The five-minute automated sanity run

```
scripts/dev-up.sh --no-kafka        # or without --no-kafka when Kafka is running
qa/smoke.sh                         # 59 checks, about 50 s; --quick skips the endorsement upload
node qa/portal-smoke.js             # opens the portal in a real browser as a broker and as HR (needs Playwright, see the file's header)
```
`smoke.sh` drives the stack only through the doors a user or an operator has and prints PASS or FAIL for each: sign-in and tenant isolation; **the lock** (direct calls to every core service with a valid token are refused, the actuator controls, metrics token, super credentials); the **job controls** with the wrong and the right job-killer password and the audit trail;
the **TPA offline upload** (preview, refusals with reason codes, read-only and other-tenant limits, the TPA's own login); and an **endorsement through the real Celery pipeline**, checking in Flower that each stage ran on its own worker. It is safe to repeat (fresh employee codes and PANs each run, tokens cached for 8 minutes because sign-in is rate limited).
If it is red, the first failing line says what; `.dev/logs/<window>.log` says why (§4).

## 2. Manual sanity: what a person should try

A run takes about an hour. Tick them off; each says what you should see.

**Sign-in and tenancy**
- [ ] Broker `meera.kapoor@northwind.example` signs in (password `Str0ng-Pass-123!`); HR `priya.menon@acme.example`; read-only `nisha.rao@...`; other broker `admin@southbridge.example`. A wrong password says "Sign-in failed", not which half was wrong.
- [ ] Southbridge sees none of Northwind's policies or employers. A URL typed by hand for someone else's record is a 404, not a 403.
- [ ] The read-only broker sees pages but has no upload or approve buttons, and the API refuses the writes.

**Endorsements** (`run.html` §10)
- [ ] Add people with the grid (up to 100 employees) and with a spreadsheet (above 100). Mistakes are marked on the row as you type; nothing reloads the page.
- [ ] A file with a bad date, a duplicate PAN, an impossible relation: the good rows go ahead on their own (`VALIDATION_PARTIAL` is passed through; set `PARTIAL_AUTO_CONTINUE=false` to hold it for a person), the upload page lists every problem line by line, and "Download as a spreadsheet" gives the rows to fix as an `.xlsx`. A file where every row is bad stops at `VALIDATION_FAILED`.
- [ ] Too little deposit: `CD_INSUFFICIENT`; credit the deposit; Retry; `ENDORSED`; the balance drops by the premium.
- [ ] Remove people and change details, not only add. Mid-term dates pro-rate (daily vs monthly per policy).

**TPA member ids by spreadsheet** (`run.html` §11): board, template, preview then save, refusals with line numbers, save twice changes nothing, the TPA's own login sees only its policies, a password-protected file and a title-row CSV give plain messages.

**The lock** (`run.html` §12; `smoke.sh` automates it): try `curl` and Postman against ports 8000-8087 with a token you copied from the browser's network tab. Every answer should be 403. Try the same with the token and a made-up `X-Gateway-Signature`: still 403.

**Operations**: change a log level in the console and watch the service's log change without a restart; kill a pending job from the Jobs tab (wrong password first); watch `scripts/jobctl.sh audit` record both.

**Things people forget**: dark mode, a 360 px wide window, keyboard-only use of the grid, a 10,000-row file, two browsers editing the same grid, signing out in one tab and clicking in another, a session left idle past 10 minutes (the portal refreshes its token; the API does not).

## 3. Automation: what exists and how to add to it

| Layer | Where | Run | Count |
|---|---|---|---|
| BFF (API contract, portal pages, signing, Telescope) | `services/bff/tests` | `php artisan test` | 99 |
| identity | `services/identity-service/tests` | `pytest -q` | 62 |
| endorsement engine (rules, pricing, Celery topology, internal API) | `services/endorsement-service/tests` | `DATABASE_URL=... pytest -q` | 341 |
| notification | `services/notification-service/tests` | `pytest -q` | 42 |
| read-service | `services/read-service/tests` | `TEST_MONGO_URI=... pytest -q` (needs MongoDB) | not run in the build sandbox |
| policy / billing / organization / integration / sheet-generator / admin | `services/*/src/test` | `DATABASE_URL=jdbc:... mvn test` | 101 / 51 / 38 / 56 / 66 / 8 |
| whole stack through its doors | `qa/smoke.sh` | stack running | 59 checks |
| the portal in a browser | `qa/portal-smoke.js` | stack running + Playwright | 11 pages |

**Rules that keep tests honest here**
1. The suites **truncate tables**. Point `DATABASE_URL` at a scratch database (`createdb scratch1`), never `ensuretech_*`. The second PostgreSQL on port 5433 (`run.html` §2) is the clean way.
2. Tests that exercise the lock set `gateway.secret` and switch `allow-unguarded` off themselves; everything else runs with the guard relaxed (`src/test/resources/config/application.properties`, `GATEWAY_ALLOW_UNGUARDED` under pytest). If you add a test for a new endpoint, add a line for it in `qa/smoke.sh` section 2 so the lock is checked live too.
3. Spring tests that need a real worker thread use the `FakeWorld` HTTP stubs (real sockets): see `JobControlTest` for blocking a TPA call and killing the job.
4. The contract the BFF exposes is data: `services/bff/contract/*.php`. `ContractConformanceTest` calls every endpoint in it, so a new endpoint is tested by being added.
5. Write new browser checks as lines in `STEPS` of `qa/portal-smoke.js` (path, expected heading). A fuller end-to-end suite should be built on Playwright against the seeded database (`\set reset 1` before each run gives a known state).

**Not set up**: continuous integration. Nothing in the repository runs these automatically on a push. A CI job would need PostgreSQL 16, Redis, Python 3.13, JDK 21 and PHP 8.3; the commands above are the steps.

## 4. Agile debugging by hand: symptom, then tool

| You see | Look at | How |
|---|---|---|
| A portal page is blank or errors | **Telescope** `http://localhost:8090/telescope` | the request, its exception, and every call the BFF made to a core service with timing and status |
| "Why is this API slow?" (Python services) | **Silk** `:8000/silk/`, `:8083/silk/` (`dev-up.sh --devtools`) | per-request SQL, query count, time per query |
| An endorsement is stuck | **Flower** `http://127.0.0.1:5555` | which task ran where, retries, failures; the job id is in each task's arguments |
| A TPA sync is stuck or looping | **Jobs tab** of the console `http://127.0.0.1:8100`, or `scripts/jobctl.sh list` | status, attempts, last error, whether a worker thread is on it; kill or requeue |
| A Java service is hot or hung | console, the service, **Threads** (thread dump) | what every thread is doing right now |
| Too little (or too much) logging | console, **Loggers** | set a package to DEBUG or back, live, no restart |
| Events not arriving between services | **Kafka UI** `:8088` (`observability-up.sh`), then the outbox tables | topic messages, consumer lag; `outbox.outbox_events` rows stuck in PENDING mean the relay is not running (beat for the engine, the Spring relay jobs for the rest) |
| "Is it the database?" | console, metrics `hikaricp_connections_*`; Silk for Python | pool exhaustion, slow queries |
| Rates and trends | **Grafana** `:3000` | requests, latency, errors, JVM, Celery, the TPA upload counters |
| A value you want to see while coding | **Ray** (`ray($x)`; `from config.devtools import ray`) | pushed to the desktop app |
| The raw story | `.dev/logs/<name>.log` and the tmux window of the same name | `tail -f`; the window stays open with the reason when a process exits |

**Following one request.** The BFF mints (or accepts, if it matches `[A-Za-z0-9._-]{8,64}`) an `X-Correlation-Id`, returns it on the response, and every service logs it in square brackets on every line: `INFO [abc123…] … access : POST /api/v1/… -> 202 41 ms`. It travels over HTTP, gRPC (`x-correlation-id` metadata), Celery (message header) and, for events, the payload's `correlation_id`. So `grep -h "<id>" .dev/logs/*.log | sort` shows one request across bff, engine, the three workers, policy and billing. Send your own id with `-H "X-Correlation-Id: my-test-001"` to make it easy to find. Background work with no request carries `task-<celery id>` or `sync-<sync job id>`. The engine job's `correlation_id` is the one its events carry, and a log line at job creation links the request id to the job id. `qa/smoke.sh` checks this end to end.

**Resetting**: `scripts/dev-down.sh`; fresh data with `\set reset 1` then `\i setup.sql` as superuser (and the Kafka topics and MongoDB database if you use them); the tmux logs and Silk/Flower stores live under `.dev/` and can be deleted.

## 5. What has and has not been verified (read before trusting a result)

Verified by running it, on Linux, in a build sandbox: the lock on every service; the offline upload; the Celery pipeline end to end (Redis broker, three worker groups, beat, Flower); Spring Boot Admin with four services; job kill, requeue, lockout and audit; Telescope, Silk; Prometheus scraping Spring and Flower with the dashboard's queries; `\i setup.sql` and reset; the tmux launcher with `--no-kafka`; all suites above.
**Not verified**: Kafka-dependent flows (the engine's outbox to Kafka, policy to billing hand-offs, dashboards from the read-service, notifications); MongoDB/FerretDB and the read-service tests; **RabbitMQ** as broker (dead-letter queues, priorities); everything in `docker-compose*.yml`; the Ray desktop app and Grafana's rendering; macOS and Windows (WSL2); the install commands for a clean laptop; continuous integration; a load test of the whole stack (the only measured figures are in `docs/tpa-offline.md`).

## Break glass, live request/SQL view and load tests

- **Break glass** (`docs/break-glass.md`): act as one named user straight against the core services when the BFF is down. `scripts/breakglass.sh`. Section 3b of `qa/smoke.sh` checks it.
- **Live requests and SQL** (`docs/observability.md`): `http://127.0.0.1:8100/queries.html`, same login as the console. Use it while testing: filter by your user id or the page's path and see the controller method and every query.
- **Load tests** (`docs/load-test.md`): `qa/load/loadtest.py`. Time a 1000-row sheet end to end, run sheets in parallel, hammer the list endpoints, and compare with the legacy system on the same sheet.
- **QA timing page** (`/app/qa/timing`, needs `BFF_QA_TOOLS=true`): buttons that upload a generated 1000-row sheet (valid, mixed, all invalid) and time every step to the result and to the error sheet. See the end of `docs/load-test.md`.
