# Running the BFF against the full local stack

Verified end to end: Postgres, Redis, FerretDB (Mongo wire, for read-service), Kafka (KRaft), identity, organization, policy (+gRPC), billing (+gRPC),
endorsement engine (uvicorn, Celery eager), integration, read-service (+ projector), and the BFF with its portal.

## Order and settings that matter
| Piece | Setting |
|---|---|
| Shared secret | One random value (32+ characters) in `BFF_GATEWAY_SECRET` (BFF) and `GATEWAY_SECRET` (every core service). The BFF signs every call; the services refuse anything unsigned (or without a SYSTEM token). Missing secret = the service will not start. See `docs/core-lockdown.md`. |
| Java services | `KAFKA_BOOTSTRAP_SERVERS`, `JWT_PUBLIC_KEY_LOCATION=file:…/identity-service/keys/jwt-public.pem`, `REDIS_*` (policy), `GRPC_PORT` 9090 (policy) / 9091 (billing) |
| Engine | `uvicorn config.asgi:application` (SSE needs ASGI), `CELERY_TASK_ALWAYS_EAGER=true` when there is no RabbitMQ, `IDENTITY_TOKEN_URL` + a service account (`manage.py service_account create engine`) so it can call policy/billing over gRPC |
| Engine outbox | Normally a Celery beat task (`apps.core.tasks.relay_outbox`). Without a broker, call it in a loop. Nothing reaches Kafka (and so read-service) otherwise. |
| read-service | `MONGO_URI` (FerretDB needs its Postgres database created `ENCODING 'UTF8' TEMPLATE template0`), run `manage.py run_projector` as a second process. Start Kafka and let the topics exist **before** the projector, or restart it after the first events. |
| Data to seed | A row in `organization.brokers` for each broker id used in tokens (no API creates brokers). Employers created through the API reach policy-service by event. |
| BFF | `UPSTREAM_*` for each service, `BFF_JWT_PUBLIC_KEY_PATH`, `SESSION_DRIVER=file` + `SESSION_ENCRYPT=true` for the portal |

## What the run found (all fixed on this branch)
- Java services returned an **empty 403** for unreadable JSON and for any unhandled error: the security chain denied Spring's error dispatch. Now 400 / proper error body.
- BFF: Spring problem `instance` shown as a field error; policy `issues:[{code,message}]` flattened to empty strings; the SSE `retry:` preamble turned into a fake
  "unreadable event"; empty JSON objects (`{}`) became `[]`; midterm-dependant rules need `windowDays`/`startBasis` upstream (BFF now exposes snake_case).
- Not bugs, but worth knowing: a funded account cannot be merged into a pool; GROUP pools need an `employer_id`; policy rate tables are `{"flat": n}` for FLAT premium type.

## Portal
`/login`, `/app/*` — server-rendered Blade pages inside the BFF. They call the BFF's own API **in-process** (a sub-request through the same kernel: same auth,
limits, shaping, no network hop). Tokens stay in the server-side (encrypted) session; the browser only holds a session cookie. CSRF on every form, own CSP
(no inline script without nonce), no caching.

Not exercised here (need external parties): real TPA calls (the SSRF guard correctly refused the unresolvable test host), insurer submission, e-cards, email/SMS.
