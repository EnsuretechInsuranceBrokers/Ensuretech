# Manual 1000-row test on an 8 vCPU server

I cannot run this on 8 vCPU: the build sandbox has 4 shared vCPU, so nothing here was measured on your hardware. This page gives the settings I would use on your 8 vCPU / 15 GiB server and the steps to time one 1000-row sheet by hand. Numbers measured on the sandbox (1.0 to 1.4 s for 1000 rows, nine-fold fewer cores' worth of headroom) are in `docs/load-test.md`.

## Settings for 8 vCPU / 15 GiB (the server also runs other things: about 5.7 GiB was in use)

**PHP-FPM pool** (`/etc/php/8.3/fpm/pool.d/www.conf`, or your own pool)
```ini
pm = static
pm.max_children = 20          ; 2 to 3 per vCPU: the BFF mostly waits on the core services
pm.max_requests = 5000
request_terminate_timeout = 120s
clear_env = no                ; the BFF reads BFF_GATEWAY_SECRET, the JWT key path, UPSTREAM_* from the environment
```
**OPcache** (`/etc/php/8.3/fpm/conf.d/99-bff.ini`; restart php-fpm on every deploy because timestamps are not checked)
```ini
opcache.enable=1
opcache.memory_consumption=256
opcache.interned_strings_buffer=32
opcache.max_accelerated_files=20000
opcache.validate_timestamps=0
opcache.jit=tracing
opcache.jit_buffer_size=64M
```
**nginx** (server block): `fastcgi_pass` to the pool's socket, `client_max_body_size 100m;`, and **do not** set `fastcgi_keep_conn on` or an upstream `keepalive`; with a static pool they pin workers and the site stalls (seen in testing).

**BFF, once per deploy**, from `services/bff` with the production environment loaded: `php artisan config:cache && php artisan route:cache`, and `composer dump-autoload -o`. Set the core-service addresses by IP (`UPSTREAM_POLICY=http://127.0.0.1:8082` and so on), not `localhost`. Run `php artisan config:clear` before running the BFF's tests or changing the environment.

**Celery workers** (engine; a 1000-row sheet is 2 chunks of 500, so more than 2 to 3 processes per stage will not make one sheet faster; they help when several sheets arrive together)
```
celery -A config worker -n ingest@%h      -Q csv.ingestion,gui.batch,maintenance -c 2
celery -A config worker -n validation@%h  -Q validation.high,validation.standard -c 3
celery -A config worker -n computation@%h -Q computation -c 3
```
**Engine web process** (`uvicorn config.asgi:application`): leave the database pool at its default (`DB_POOL_MAX=8` per process; each Celery process has its own). Keep PostgreSQL's `max_connections` at 200 or more: five Spring services take 10 each, plus the engine, identity and workers.

**Spring services:** nothing to change for this test. Make sure `QUERYLOG_ENABLED` is **false** (it is a debugging aid and slows requests down) and that Telescope is off in the BFF (`TELESCOPE_ENABLED=false`, `APP_ENV=production`).

**Rate limits:** one upload is far under the BFF's 20-uploads-a-minute limit, so leave them. Only raise them for load tests with many uploads.

## The test, by hand
1. Warm up: do one small upload first (JVM and caches), then measure.
2. **Generate the sheet.** Open the sheet-generator's `index.html` and make a 1000-row ADD sheet for the policy, or run `qa/load/loadtest.py gen --rows 1000 --out sheet.csv` for the exact sheet used in my runs (334 employees, each with a spouse and a child; fresh employee codes and PANs on every run). Use new employee codes each time: the engine refuses a member who is already covered.
3. **Check the deposit.** The policy's CD balance must exceed the premium for 1000 members (about ₹83 lakh at ₹5 lakh sum insured in my data, yours will differ). Otherwise the job stops at `CD_PARTIAL_SUFFICIENT`.
4. **Time it.** Start a stopwatch on pressing Upload, stop when the endorsement shows `ENDORSED`. Also note when the upload was accepted. Repeat 3 times with new codes and use the median. Note `lscpu`, `free -h` and anything else busy.
5. **Where the time went**, if you want it: Flower (`http://127.0.0.1:5555`, through an SSH tunnel) shows `ingest_csv`, `validate_chunk`, `price_chunk`, `apply_chunk` with their durations; the engine's job status moves through `REQUEST_RAISED`, `VALIDATION_*`, `PREMIUM_CALC_*`, `CD_CHECK_INITIATED`, `ENDORSEMENT_*`, `ENDORSED`.
6. Time the legacy system on the same sheet, same policy shape, same effective date, from pressing submit to the endorsement being done.

Fill in `docs/load-test.md` ("Comparing fairly with legacy") with both columns.
