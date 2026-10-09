# Debugging and analytics (local development only)

Everything here is for a developer's or a QA engineer's machine. None of it is installed, enabled or reachable on a server, and none of it is needed to run the product.
`run.html` §16 has the same table in short form; `scripts/dev-up.sh --help` lists the switches.

| Tool | Where | Switch | Status when this was written |
|---|---|---|---|
| Laravel Telescope | BFF, `/telescope` | `dev-up.sh` (default on), `--no-telescope` | request recording and hidden fields tested; page opened over HTTP, not looked at in a browser |
| Django Silk | identity, engine, read, notification: `/silk/` | `dev-up.sh --devtools` | tested end to end (requests recorded, SQL counted, headers masked, sign-in calls not recorded, nothing in the service database) |
| Django Debug Toolbar | same services, HTML pages only | `--devtools` | installs and loads; JSON responses carry no HTML, so it appears on Silk's pages and the browsable API, not on API calls |
| Ray | BFF (`ray()`), Django (`from config.devtools import ray`), SQL via `RAY_QUERIES` | `--ray` | the payloads sent were captured from a local listener and match what Spatie's own PHP client sends; the Ray desktop app itself was **not** available here, so the display is untested |
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

## Not included, and why

- **Laravel Horizon**: it is the dashboard for Redis queues. The BFF has none (every job would run inline), so the page would always be empty. It makes sense the day something is queued, for example sending the uploaded offline sheets' audit export.
- **Celery Flower**: locally the engine runs tasks inline (`CELERY_TASK_ALWAYS_EAGER`), so there is nothing to watch.
- **OpenTelemetry / Jaeger tracing**: planned in the architecture, not wired. It needs trace headers passed through the BFF and every service.
- **Metrics from the Django services and the BFF**: only the Spring services expose Prometheus metrics today; Silk and Telescope cover the others for development.
