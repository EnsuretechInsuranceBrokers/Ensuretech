# Debugging and analytics (local development only)

Everything here is for a developer's or a QA engineer's machine. None of it is installed, enabled or reachable on a server, and none of it is needed to run the product.
`run.html` §16 has the same table in short form; `scripts/dev-up.sh --help` lists the switches.

| Tool | Where | Switch | Status when this was written |
|---|---|---|---|
| Laravel Telescope | BFF, `/telescope` | `dev-up.sh` (default on), `--no-telescope` | request recording and hidden fields tested; page opened over HTTP, not looked at in a browser |
| Django Silk | identity, engine, read, notification: `/silk/` | `dev-up.sh --devtools` | tested end to end (requests recorded, SQL counted, headers masked, sign-in calls not recorded, nothing in the service database) |
| Django Debug Toolbar | same services, HTML pages only | `--devtools` | installs and loads; JSON responses carry no HTML, so it appears on Silk's pages and the browsable API, not on API calls |
| Ray | BFF (`ray()`), Django (`from config.devtools import ray`), SQL via `RAY_QUERIES` | `--ray` | the payloads sent were captured from a local listener and match what Spatie's own PHP client sends; the Ray desktop app itself was **not** available here, so the display is untested |
| Operations console (Spring Boot Admin + Jobs) | `admin-service`, `http://127.0.0.1:8100` | `dev-up.sh` (default on), `--no-admin` | tested live: four services registered, log level changed at runtime, jobs killed and requeued, password lockout; looked at in a browser |
| Celery Flower | the engine's Celery cluster, `http://127.0.0.1:5555` | `dev-up.sh` (default on), `--no-flower` | tested live: three worker groups, every pipeline task seen, UI rendered, metrics scraped by a real Prometheus; RabbitMQ not available here (Redis broker tested) |
| Prometheus + Grafana | `scripts/observability-up.sh` | Docker | scrape config verified against a real Prometheus; the compose file and the Grafana dashboard were **not** run (see below) |
| Kafka UI | same | Docker | not run (no Docker, no Kafka in the build sandbox) |

## Laravel Telescope (BFF)

Records every request the portal and the API handle, every outgoing call the BFF makes to a core service (with timings), exceptions, log lines, cache hits and the rate limiter.
It is registered only when `APP_ENV=local` **and** `TELESCOPE_ENABLED=true`, it is a `--dev` dependency (`composer install --no-dev` does not install it), and its gate admits only a local environment.
It stores to a SQLite file (`services/bff/storage/telescope.sqlite`), so the BFF still needs no database. The headers `Authorization`, `Cookie`, `X-Gateway-*` and the fields `password`, `refresh_token`, `client_secret`, `access_token` are never stored, even locally.
Clear it with `php artisan telescope:clear`.

## Django Silk and Debug Toolbar

`DEV_TOOLS=true` (with `DEBUG=true`; the service refuses to start otherwise) adds Silk and the toolbar. Install the packages with `python ../../scripts/install_python_deps.py --devtools`
and create Silk's tables once with `python manage.py migrate silk --database=devtools` (`dev-up.sh --devtools` does both). Silk's data lives in `.dev/devtools-NAME.sqlite3`; a router keeps `silk_*` tables out of the service's own database.
Open `http://127.0.0.1:PORT/silk/`. The dashboards are used from a browser, which cannot sign requests, so while `DEV_TOOLS` is on the gateway lock lets `/silk/`, `/__debug__/` and `/static/` through; every API path stays locked.
Do not leave `DEV_TOOLS` on for the lock check in `run.html` §12.

## Ray

Ray (myray.app) is a desktop app that listens on `127.0.0.1:23517`; your code sends it values and it shows them. It is a paid app with a trial: check the licence before rolling it out to the team.
- PHP: `spatie/laravel-ray` (dev dependency). `ray($value)->label('x')->green()`. With `RAY_ENABLED=true` (local only) requests, outgoing HTTP calls and log lines also appear on their own.
- Python (the Django services): `from config.devtools import ray`, same chaining. There is no official Python package, so `config/devtools.py` sends the same JSON over the same endpoint. `RAY_QUERIES=true` shows every SQL query with its time.
- Spring: no helper. The wire format is a `POST` of `{"uuid", "payloads":[{"type":"log","content":{"values":[...]},"origin":{...}}]}` to `http://127.0.0.1:23517/`; use Spring's own logs or the Prometheus dashboard.
- With no Ray running every call is a silent no-op. Do not commit `ray()` calls, and remember what you send is whatever you pass it (tokens included).

## Prometheus, Grafana, Kafka UI

The Spring services publish metrics at `/actuator/prometheus` (the micrometer Prometheus registry is now on their classpath; before this the endpoint was listed in the configuration but did not exist).
That endpoint is behind the gateway lock like everything else; a metrics scraper gets one read-only door: `GET /actuator/prometheus` with `Authorization: Bearer $METRICS_TOKEN` (`local.env`, 32+ characters, blank = closed).
The token opens that one URL only and is not a user or SYSTEM token. `scripts/observability-up.sh` writes it where Prometheus reads it and starts the three containers (Linux or Docker Desktop with host networking, because the services listen on `127.0.0.1` only).

Dashboard "Ensuretech services": services up, requests per second and average latency per service, 4xx and 5xx, JVM heap, database connections in use, logged errors, and the TPA offline upload
(`ensuretech_offline_lines_total{outcome}` for lines saved or refused, `ensuretech_offline_upload_seconds` for time taken; both appear after the first applied upload).

No Docker? Download Prometheus and run it with `observability/prometheus.yml` (change the token path to a local file, `--web.listen-address=127.0.0.1:9099`), and use `kafka-consumer-groups.sh --describe` for lag.

## Operations console: Spring Boot Admin and the Jobs page

Flower's counterpart for the Java services. `admin-service` (port 8100, loopback, one login: `ADMIN_UI_USER` / `ADMIN_UI_PASSWORD` in `local.env`; it refuses to start without a 12+ character password) runs Spring Boot Admin over organization, policy, billing, integration and sheet-generator:
health with component details, JVM memory and threads, a **thread dump** to find what a stuck worker is doing, HTTP and DB-pool metrics, the list of `@Scheduled` jobs, and **log levels you can change at runtime** (no restart), which is the answer to "aggressive logging when you need it, quiet when you do not".
It does not cover the Django services or the BFF (use Flower, Silk and Telescope for those).

**It sits behind the lock.** The services' `/actuator/loggers`, `threaddump`, `scheduledtasks` and `metrics` answer only a SYSTEM token (service account); `health` is open without details, `prometheus` needs the metrics token. The console signs in to identity as a service account (`ENGINE_CLIENT_ID/SECRET` in `local.env`, a development value) and sends that token on every call.
`heapdump`, `env` and `shutdown` are not exposed at all: a heap dump contains tokens and passwords in memory.

**Job controls (integration-service).** The TPA sync queue is a PostgreSQL table with leases, retries and a dead-letter state. The controls live next to it, under `/api/v1/ops/jobs`, for SYSTEM tokens only:
- list and show (with whether a worker thread is on it right now), the audit trail;
- **kill** a pending or running job: a waiting one never starts; for a running one the worker thread is interrupted (its HTTP call to the TPA is cut short) and the job is marked `CANCELLED`, so it is neither retried nor dead-lettered. Whatever was already sent to the TPA stays sent; batches carry idempotency keys, so a later requeue does not duplicate them;
- **requeue** a `DEAD` or `CANCELLED` job with a fresh set of attempts.
Kill and requeue need the **job-killer password** in the `X-Job-Killer-Password` header (a header, so it is not in URLs or access logs). It exists only as a bcrypt hash (`integration.job_control_credentials`); on a first start it is hashed from `JOB_KILLER_BOOTSTRAP_PASSWORD` (a dev value in `local.env`) and the variable is then ignored; change it with `PUT /api/v1/ops/jobs/password`.
Five wrong passwords in a row lock the controls for 15 minutes, for everyone, even against the right password. Every attempt (wrong, locked, allowed, refused) is in `integration.job_control_audit`, with the service account and address.
Use it from the **Jobs** tab of the console, or `scripts/jobctl.sh list | show | audit | kill ID "reason" | requeue ID | set-password` (the password is prompted, never an argument).

**Why not JobRunr or Quartz.** Both would run a second job system beside the one we have: their own tables, their own worker threads, and a rewrite of the sync worker to fit them, for features (retries, back-off, a dead-letter state, leases that survive a crash, a circuit breaker per TPA) that the sync queue already has. JobRunr's open-source dashboard has only basic-auth protection and Quartz has no dashboard at all, so we would still have built the locked-down controls ourselves. The scheduled sweeps (outbox relay, reservation and policy expiry, the six-hourly reconcile) are listed in the console; they cannot be paused from outside, which is acceptable for jobs that take milliseconds. If a future job queue needs priorities, scheduling in the future or thousands of jobs per second, JobRunr is the one to revisit.

**Not covered.** Kafka consumers are controlled from Kafka UI (pause, offsets, lag). A single stuck thread outside the sync worker can be seen in the thread dump but not killed from outside the JVM. The sheet-generator and the other services' `@Scheduled` sweeps have no kill; only sync jobs do.

## Celery Flower

The endorsement engine is a Celery pipeline: `ingest_csv` -> `validate_chunk` (one task per ~500-row chunk, in parallel) -> `start_premium_calculation` -> `price_chunk` -> `check_cd_balance` -> `apply_chunk`, with retries (`countdown` back-off, `acks_late`, `reject_on_worker_lost`) and six isolated queues
(`csv.ingestion`, `validation.high`, `validation.standard`, `gui.batch`, `computation`, `maintenance`; each with a dead-letter queue on RabbitMQ). Flower is how you see it: which worker took which task, how long it ran, what failed and was retried, what is waiting.
- Locally `scripts/dev-up.sh` runs three worker groups (`ingest`, `validation`, `computation`), beat and Flower. An upload through the portal shows `ingest_csv` on the ingest worker, `validate_chunk` on the validation worker, and the pricing and apply tasks on the computation worker.
- Broker: RabbitMQ when it answers on 5672 (`docker compose -f docker-compose.infra.yml up -d rabbitmq`), else Redis. The queue priorities and dead-letter queues exist only on RabbitMQ; on Redis the pipeline runs without them. Only the Redis path was run in the build sandbox.
- The workers emit task events (`CELERY_EVENTS`, on by default). Flower serves `/metrics`; Prometheus scrapes it and the Grafana dashboard has a Celery row (workers online, completions, failures and retries, run time per task, tasks running per worker).
- Flower can revoke tasks and shut workers down. It listens on `127.0.0.1` with basic auth (`FLOWER_BASIC_AUTH` in `local.env`, a development value); on a real server put it on a private network behind your own authentication, never on a public address.
- `CELERY_TASK_ALWAYS_EAGER=true` (`dev-up.sh --eager`) runs tasks inline in the web process: no workers, no Flower. It exists for stepping through one task in a debugger and hides everything Celery is for.

## Not included, and why

- **Laravel Horizon**: it is the dashboard for Redis queues. The BFF has none (every job would run inline), so the page would always be empty. It makes sense the day something is queued, for example sending the uploaded offline sheets' audit export.
- **OpenTelemetry / Jaeger tracing**: planned in the architecture, not wired. It needs trace headers passed through the BFF and every service.
- **Metrics from the Django services and the BFF**: only the Spring services expose Prometheus metrics today; Silk and Telescope cover the others for development.
