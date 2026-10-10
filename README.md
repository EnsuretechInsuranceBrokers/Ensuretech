# Ensuretech

Ensuretech is an event-driven platform for group health insurance onboarding. It gives brokers, employers and their HR teams, third-party administrators (TPAs) and insurers one validated, auditable place to add, update and remove insured members, price the change, check the policy deposit, and hand clean data to the parties who need it.

It replaces a monolithic PHP system whose long-running Excel imports blocked requests and failed under load. The replacement is a set of small services behind a single public gateway (the BFF), with the heavy work done by queued background workers.

| | |
|---|---|
| Repository | https://github.com/EnsuretechInsuranceBrokers/Ensuretech |
| Project site | https://ensuretechinsurancebrokers.github.io/Ensuretech/ |
| Runbook (setup, first run, checks) | [`run.html`](run.html), published at https://ensuretechinsurancebrokers.github.io/Ensuretech/run.html |
| Documentation index | [Documentation](#documentation) below, source in [`docs/`](docs/) |
| License | Proprietary, all rights reserved (see [License](#license)) |

## Contents

1. [Background, vision and mission](#background-vision-and-mission)
2. [Project status](#project-status)
3. [Documentation](#documentation)
4. [What the platform does](#what-the-platform-does)
5. [Architecture](#architecture)
6. [Services](#services)
7. [Security model](#security-model)
8. [Access and operations policy](#access-and-operations-policy)
9. [Performance and verification](#performance-and-verification)
10. [Operations and observability](#operations-and-observability)
11. [Getting started](#getting-started)
12. [Configuration](#configuration)
13. [Testing](#testing)
14. [Repository layout](#repository-layout)
15. [Software used and its documentation](#software-used-and-its-documentation)
16. [Relationship to the legacy system](#relationship-to-the-legacy-system)
17. [Known limitations and roadmap](#known-limitations-and-roadmap)
18. [Contributing](#contributing)
19. [License](#license)

---

## Background, vision and mission

### Background

The legacy system was a Laravel monolith. Large Excel uploads crashed it, imports had to be started by hand from the command line, a single malformed column could make a TPA reject an otherwise clean enrollment, and one internal user with too much trust and too few guardrails was able to degrade the production server. Every party in group insurance onboarding (brokers, TPAs, insurers, employers, HR teams) was fighting the same fragmentation: spreadsheets in different shapes, validation that happens late, and reconciliation done by hand.

Ensuretech treats that as a shared problem and builds the shared layer: data is validated once, as early as possible, then flows to everyone who needs it with an audit trail.

### Vision

Insurance onboarding becomes background infrastructure. Brokers, TPAs, insurers and employers work from a single operational record, and the friction of enrollment largely disappears.

### Mission

Build resilient, neutral, standards-driven onboarding infrastructure that removes spreadsheet chaos, validates data before it enters the ecosystem, and makes the shared operational record valuable enough that every participant spends less time reconciling and more time serving their customers.

### Who it serves

| Stakeholder | Problem addressed |
|---|---|
| Brokers | Onboarding effort, rejection loops, manual operations work |
| Employers and HR teams | Poor onboarding experience, spreadsheet handling, late validation feedback |
| TPAs | Malformed enrollment data, reconciliation effort, member id and e-card exchange |
| Insurers | Inconsistent ingestion, compliance gaps, limited audit visibility |
| Operations | Manual reconciliation, cascading failures, little visibility into running work |

---

## Project status

This is a working platform that has been exercised end to end on a developer machine and by automated suites. It has not yet been through production hardening. The table separates what has been verified from what has not.

| Area | State |
|---|---|
| Endorsement pipeline (CSV and Excel upload, validation, pricing, deposit check, roster write) | Implemented and verified by automated tests and live runs |
| Result spreadsheet in the legacy `AddMemberError.xlsx` layout | Implemented; Add layout verified against a real legacy file, Remove and Update layouts are an assumption |
| Browser portal for brokers and HR, and a "Settings and tools" screen for every BFF API | Implemented; browser-checked for read operations, write operations not yet hand-tested |
| Request signing, tenant isolation, core lock, break-glass access | Implemented and tested |
| Observability and operations tooling | Implemented (see [Operations and observability](#operations-and-observability)) |
| Kafka-driven flows (cross-service events, notification mail, dashboards fed by events) | Implemented, not verified end to end against a real broker |
| RabbitMQ as the Celery broker | Supported, not exercised; Redis is used for local runs |
| Read-service (MongoDB projections) and the assistant | Implemented; its test suite needs MongoDB and has not been run in the build environment; answers are extractive unless a Claude API key is configured, and that path was only tested against a stub |
| Transport encryption between services | Not configured by default; see [Security model](#security-model) |
| Independent security assessment | Not yet performed |
| Payment service | Designed (see [Access and operations policy](#access-and-operations-policy)); not yet built |

Details and measured figures are in [`docs/load-test.md`](docs/load-test.md) and [`docs/qa-handbook.md`](docs/qa-handbook.md).

---

## Documentation

Everything below lives in this repository and is also available on the project site at https://ensuretechinsurancebrokers.github.io/Ensuretech/.

| Document | Contents |
|---|---|
| [`run.html`](run.html) | The runbook: prerequisites, database setup, first run, a guided walkthrough, troubleshooting, and the tooling index |
| [`docs/qa-handbook.md`](docs/qa-handbook.md) | How to test by hand and with automation, expected results, and a symptom-to-tool debugging table |
| [`docs/full-stack-local.md`](docs/full-stack-local.md) | Running every component locally and the settings that matter |
| [`docs/request-signing.md`](docs/request-signing.md) | How the BFF signs each call and how a core service authenticates and authorizes it |
| [`docs/core-lockdown.md`](docs/core-lockdown.md) | The rules that keep core services unreachable except through the BFF |
| [`docs/break-glass.md`](docs/break-glass.md) | Emergency, audited access to act as one named user when the BFF is unavailable |
| [`docs/observability.md`](docs/observability.md) | Telescope, Silk, Flower, Spring Boot Admin, the live request and SQL view, Prometheus and Grafana, and how to open each |
| [`docs/load-test.md`](docs/load-test.md) | Load-test method and measured results, including the comparison with the legacy system |
| [`docs/manual-test-8vcpu.md`](docs/manual-test-8vcpu.md) | Settings and steps for a hand test on an 8 vCPU server |
| [`docs/aws-cost-estimate.md`](docs/aws-cost-estimate.md) | Planning estimate of monthly cloud cost by stage |
| [`docs/runtime-dependencies.md`](docs/runtime-dependencies.md) | Every external dependency, what it is for, and whether it costs money |
| [`docs/intake-routing.md`](docs/intake-routing.md) | How an upload is routed between the grid and the file path |
| [`docs/tpa-offline.md`](docs/tpa-offline.md) | The offline TPA member id and e-card exchange |
| [`docs/adr/`](docs/adr/) | Architecture decision records |
| [`docs/legacy/`](docs/legacy/) | Reverse-engineered legacy sheet formats, raters and validation rules |
| Per-service `README.md` files | Details of each service under [`services/`](services/) |

---

## What the platform does

### Endorsement lifecycle

An endorsement is a change to the insured population of a policy: adding, removing or updating members. One upload becomes one job that moves through a fixed state machine.

```
REQUEST_RAISED
  -> FILE_STREAMED -> VALIDATION_QUEUED -> VALIDATION_IN_PROGRESS
  -> VALIDATION_PASSED | VALIDATION_PARTIAL | VALIDATION_FAILED
  -> PREMIUM_CALC_QUEUED -> PREMIUM_CALCULATED
  -> CD_CHECK_INITIATED -> CD_SUFFICIENT
  -> ENDORSEMENT_QUEUED -> ENDORSEMENT_IN_PROGRESS -> ENDORSED
```

Blocked and failed states (insufficient deposit, a pricing failure, a billing outage) are recoverable with a retry once the cause is fixed. Every transition is written to an append-only audit table.

| Stage | Work done | Where |
|---|---|---|
| Ingest | Stream the file in chunks, normalise headers by synonym and similarity, store raw and normalised rows | Celery worker group `ingest` |
| Validate | Per-row rules (names, dates, relations, family construct, duplicates, policy rules), one task per chunk, in parallel | Worker group `validation` |
| Price | Per-family premium from the policy's rate card, pro-rata by effective date, tax | Worker group `computation` |
| Deposit check | Ask billing-service whether the policy's deposit covers the premium (gRPC) | Worker group `computation` |
| Endorse | Write roster members idempotently, emit events through a transactional outbox | Worker group `computation` |

A job where some rows fail and others pass continues with the rows that passed; the failures stay on the job. A job where every row fails stops at `VALIDATION_FAILED`. Setting `PARTIAL_AUTO_CONTINUE=false` holds a partly failed job until a person confirms it.

### Ways to submit data

| Path | Description |
|---|---|
| File upload | CSV or Excel (`.xlsx`) files. Columns are matched by name and similarity, so files from other HR systems can be read; mappings can be saved and reused. Files stream through the pipeline in chunks, so memory use does not grow with file size. |
| Grid | For small changes, an on-screen grid that validates every row as it is typed, up to a configurable number of employees; above that the portal offers a template spreadsheet for the policy. |
| Offline TPA exchange | For TPAs without an integration: download the list of members waiting for ids, send it to the TPA, upload the TPA's sheet; ids and e-card links are matched back to members. See [`docs/tpa-offline.md`](docs/tpa-offline.md). |

### Result spreadsheet

After an upload, the page offers "Download as a spreadsheet". The file follows the legacy format: one sheet named `Worksheet`, the legacy column layout (106 columns for Add), one row for every uploaded row in upload order, and two final columns, `Status` and `Reason`. Rows that were endorsed read "Member added successfully."; rows that failed read "Failed to add." with the reason; rows that passed but have not been endorsed yet read "Not yet added." so they are never mistaken for completed work. Values that begin with `=`, `+`, `-` or `@` are written as text so a hostile cell cannot execute as a formula.

### Multiple employers and policies

Brokers work across many employers and policies. Upload and add/remove forms offer an Employer dropdown that narrows the Policy dropdown. Employer users see only their own employer. Isolation between tenants is enforced by the core services, not the portal (see [Security model](#security-model)).

---

## Architecture

```
                         Browser (server-rendered portal, small JavaScript modules)
                                          |
                                     HTTPS (terminate at nginx)
                                          |
        +---------------------------------v----------------------------------+
        | BFF: Laravel (nginx + PHP-FPM)                                     |
        | Portal pages, public JSON API, JWT verification, rate limits,      |
        | contract allowlist and response shaping, request signing           |
        +---------------------------------+----------------------------------+
                                          | signed HTTP (one HMAC per call)
     +-------------+-------------+--------+-------+--------------+-----------+------------+
     |             |             |                |              |           |            |
 identity    organization    policy          endorsement      billing   integration   read-service
 (Django)    (Spring Boot)   (Spring Boot    (Django +        (Spring    (Spring       (Django +
                              + gRPC)         Celery)          Boot       Boot)         MongoDB)
                                                               + gRPC)
     |             |             |                |              |           |            |
     +-------------+-------------+----------------+--------------+-----------+------------+
                                          |
        PostgreSQL (one database per service)   Redis (cache, Celery broker)
        Kafka (events)   RabbitMQ (optional Celery broker)   MongoDB (read models)
```

Principles:

1. **One public entry point.** Only the BFF is reachable by users. Core services refuse any request that is not signed by the BFF (or made with a service account's super credentials).
2. **Workload isolation.** The endorsement engine runs three Celery worker groups (ingest, validation, computation) on separate queues with their own concurrency, so a very large upload cannot starve other work.
3. **Database per service.** Each service owns its PostgreSQL database; services share contracts (`shared/contracts`), not tables.
4. **Events through an outbox.** State changes are written to an outbox table in the same transaction and relayed to Kafka, so an event is never lost or published for a change that rolled back.
5. **Idempotent writes.** Jobs, applied rows and ledger entries carry keys so a redelivered task or a repeated request does not apply twice.
6. **Graceful degradation.** Calls to other services have deadlines; failures park a job in a recoverable state instead of losing it.

---

## Services

| Service | Technology | Port | Responsibility | Data |
|---|---|---|---|---|
| `bff` | Laravel 13, PHP 8.3, nginx + PHP-FPM | 8090 | Portal and public API; verifies tokens, applies rate limits, enforces the API contract, shapes responses, signs calls to core services | Stateless (sessions on disk or Redis) |
| `identity-service` | Django 6.1, Django REST framework, SimpleJWT | 8000 | Users, roles, tenant scoping, JWT issuance (RS256), service accounts and the super-credential token, break-glass grants | PostgreSQL |
| `organization-service` | Spring Boot 4.1, Java 21 | 8081 | Employers, parent and child employers, employer options, branches, announcements | PostgreSQL |
| `policy-service` | Spring Boot 4.1, gRPC | 8082, gRPC 9090 | Policies, rate cards, family construct and relation rules, contributions, rounding, mid-term rules, enrolment windows, premium quotes | PostgreSQL, Redis cache |
| `endorsement-service` | Django 6.1, Celery 5.6, uvicorn | 8083 | The endorsement engine: intake, validation, pricing, deposit check, roster, result sheet, live progress over server-sent events | PostgreSQL, Redis or RabbitMQ |
| `billing-service` | Spring Boot 4.1, gRPC | 8084, gRPC 9091 | Policy deposit (CD) accounts, ledger, statements, thresholds, top-up requests, pools | PostgreSQL |
| `integration-service` | Spring Boot 4.1, Resilience4j | 8085 | TPA connections, sync jobs, reconciliation, offline member id and e-card exchange, job controls | PostgreSQL |
| `sheet-generator-service` | Spring Boot 4.1, Apache POI | 8086 | Policy-driven sample and test sheets (Excel and CSV) and a standalone HTML generator | PostgreSQL |
| `read-service` | Django, MongoDB | 8087 | Read models built from events, dashboards, reports, knowledge documents and the assistant | MongoDB |
| `notification-service` | Django | n/a | Consumes events and sends email | PostgreSQL |
| `admin-service` | Spring Boot 4.1, Spring Boot Admin | 8100 | Operations console, background job controls, live request and SQL view | None |

Shared infrastructure for local use is defined in [`docker-compose.infra.yml`](docker-compose.infra.yml) (PostgreSQL 16, Redis 7, MongoDB 7, RabbitMQ 3.13, Kafka 3.9, Mailpit) and [`docker-compose.observability.yml`](docker-compose.observability.yml) (Prometheus, Grafana, Kafka UI).

---

## Security model

Full detail is in [`docs/request-signing.md`](docs/request-signing.md) and [`docs/core-lockdown.md`](docs/core-lockdown.md). Summary:

### Two separate proofs on every call

| Question | Proof | Checked by |
|---|---|---|
| Did this call come through the BFF? | A signature: HMAC-SHA256 over a version tag, timestamp, one-time nonce, HTTP method, exact path, query string and a hash of the user's token, using a secret shared by the BFF and the core services | The gateway guard in each core service, before anything is parsed or any database is touched |
| Who is the user and what may they do? | The user's access token (RS256 JWT issued by identity-service) | The BFF first, then again by every core service, using identity's public key |

A signature is valid for one method, path, query and token, for 60 seconds, once. The secret never travels. A request with neither a valid signature nor a valid super-credential token receives an empty 403.

### Authorization in the core services

1. Role rules per URL group decide which roles may call which routes; anything not listed is denied.
2. Tenant identifiers (broker, employer, TPA) are taken from the verified token, never from the request, and every query is scoped by them. An identifier that belongs to someone else answers 404.
3. Method-level rules narrow access further (for example, only a broker administrator may record a deposit).
4. Operational endpoints (`/actuator/**`, `/api/v1/ops/**`) accept only SYSTEM tokens. The gRPC services accept only service accounts.

### Privileged access

| Mechanism | Purpose |
|---|---|
| SYSTEM token (service account) | A short-lived token obtained with a client id and a 384-bit secret stored only as a hash; used by services and operators for internal and operational endpoints. It has no tenant, so it cannot act as a broker or employer. |
| Break-glass | When the BFF is unavailable, an operator holding both super credentials and the break-glass password can obtain a 15-minute token to act as one named, existing user. Every attempt is recorded, the token carries the operator and reason, the live request view flags its use, and it has no portal or frontend interface. See [`docs/break-glass.md`](docs/break-glass.md). |
| Job-killer password | A separate hashed password required to stop or requeue background sync jobs. |
| Honeypot routes | Bait routes in the BFF that look like administrative endpoints, answer like any unknown path, and raise a silent alert when touched. |

### Web security

The portal escapes all output, sends a nonce-based Content-Security-Policy and standard security headers, uses CSRF protection on every state-changing request, and stores tokens server-side so they never reach browser scripts. File downloads are checked against the content types the API contract allows.

### What is not covered yet

- The signature proves origin and integrity but does not encrypt. Traffic between the BFF and core services is plain HTTP unless TLS or mutual TLS is added; keep it on loopback or a private network, and terminate HTTPS for browsers at nginx.
- gRPC listeners bind to all interfaces by default and are restricted to service accounts; place them behind a firewall.
- No independent penetration test or dependency audit has been performed. Recommended before real data: OWASP ZAP against the BFF, a dependency vulnerability scan, and TLS everywhere.

---

## Access and operations policy

This section records the architectural decisions on privileged access, the BFF's role as a contract boundary, and the planned payment service.

### Source ownership is separate from production access

Source-code access, infrastructure access, database access and application-level access are separate privileges. A small, highly trusted group owns the core backend source and makes permanent fixes. A wider operations group may need production access to diagnose and mitigate incidents without any source access. This limits the damage if one account or credential is compromised.

### Network boundary

Core services are private and not publicly routable. Normal traffic is client, then BFF, then core service. Privileged humans who need direct access to internal APIs enter through the corporate VPN, and the VPN provides network reach only; individual authentication and narrow authorization still apply. Postman is the supported manual client, but it is not a security control: a request from `curl`, Postman or a script is the same HTTP request. The controls are the private network, VPN, individual identity, authentication, authorization and narrow endpoint permissions.

### The BFF as the public contract boundary

The BFF owns the public representation of backend data. It validates and sanitises input, removes internal fields, normalises values, and composes responses. Every endpoint, field and path parameter that the portal can use is declared in the contract files (`services/bff/config/contract.php` and `services/bff/contract/`); anything not declared is refused.

Controlled response transformations let a defect in a core service be contained at the boundary while a permanent fix is prepared. They are limited to representation: filtering, renaming, normalising enum values, composing responses and safe emergency masking. Business calculations, domain invariants, authorization decisions and financial state stay in the core services. A temporary mitigation must record an identifier, the endpoint, the reason, an owner, the date, the removal condition and a review date, so the BFF does not accumulate undocumented business logic.

### Production debugging as a distinct privilege

An operations role sits between ordinary application access and source ownership. It can inspect requests, sanitised responses, job status, queues and validation failures, retry safe and idempotent jobs, reprocess approved operations, apply approved BFF mitigations and disable a broken client capability. It cannot modify core source, run arbitrary SQL, deploy arbitrary code, read backend secrets, change authentication rules or perform unrestricted data changes. Incidents escalate from operations to core engineering only when the fix genuinely requires a core change.

### Vulnerability response

Findings from a bug bounty or red team go to the core owner, who decides whether the BFF can safely contain the issue. If it can, a mitigation is deployed at the BFF immediately and the permanent fix follows, after which the mitigation is removed. If it cannot, the core fix proceeds directly.

### Payment service (planned)

The payment service will be an intentional exception to the normal path: an internal service-to-service workflow that talks to the core directly instead of through the BFF. The exception does not bypass security. It requires private networking, strong service identity with mutual TLS or equivalent, explicit authorization, an endpoint allowlist, a payment-specific contract (authorize, capture, refund, reconcile, read one payment), idempotency, and validation. It receives a narrow set of capabilities, not general backend access, and it does not depend on the client-facing response shaping done by the BFF. This service is not implemented yet.

---

## Performance and verification

Method, raw results and caveats are in [`docs/load-test.md`](docs/load-test.md). All figures below were measured on a 4 vCPU, 15 GiB build machine running every component at once (databases, services, workers and the load generator together), with the BFF on nginx and PHP-FPM. Treat them as a floor for the software on that hardware, not as a comparison with a production server of a different size.

| Case (1000 rows) | Result |
|---|---|
| All rows valid | about 1.0 s from upload to `ENDORSED` |
| 996 valid and 4 invalid | about 1.0 to 1.3 s to `ENDORSED`, with the 4 failures listed |
| All rows invalid | about 0.7 s to `VALIDATION_FAILED`; the result spreadsheet downloads in about 0.3 s |
| Ten 1000-row uploads at once | all complete in about 6 s |
| 10,000 and 25,000 rows | about 7.4 s and 19 s |
| BFF read requests, 20 concurrent users | about 170 to 225 requests per second, median 35 to 61 ms |

The legacy system's timings for the same sizes, recorded on production hardware, and the protocol for a like-for-like comparison are in the same document. To repeat the measurements:

```bash
scripts/dev-up.sh --bench --no-kafka --with-sheet --skip bff   # services without debugging overhead
scripts/bff-fpm.sh --bench                                      # the BFF on nginx + PHP-FPM
python3 qa/load/loadtest.py endorse --rows 1000 --uploads 5
```

The portal also has a QA timing page (`/app/qa/timing`, available only when `BFF_QA_TOOLS=true`) with buttons that build a sheet, upload it and time each step.

---

## Operations and observability

Each tool binds to `127.0.0.1`; reach it from your own machine, over an SSH tunnel for a server, and never expose these ports publicly. Instructions and logins are in [`docs/observability.md`](docs/observability.md).

| Tool | Shows or controls | Applies to |
|---|---|---|
| Live requests and SQL (operations console, `/queries.html`) | Each request as it happens: user, role, tenant, controller method, every SQL statement with timing, repeated-statement warnings, IST timestamps, free-text filtering | Spring services |
| Spring Boot Admin (operations console) | Health, metrics, threads, log levels, scheduled jobs | Spring services |
| Jobs page and `scripts/jobctl.sh` | List, kill and requeue background sync jobs, protected by the job-killer password | integration-service |
| Celery Flower | Workers, queues, every task and its retries; revoke tasks and manage workers | Endorsement engine |
| Django Silk and Debug Toolbar | Every request with its SQL and timings (development only, requires `DEBUG` and `DEV_TOOLS`) | Django services |
| Laravel Telescope and Ray | Requests, outgoing calls, exceptions, logs (local environment only) | BFF |
| Prometheus, Grafana, Kafka UI | Metrics, dashboards, topics (`scripts/observability-up.sh`) | All |
| Correlation id | One id on every log line of every service; trace a request with `grep -h <id> .dev/logs/*.log` | All |
| Mailpit | Catches outgoing mail locally | notification-service |

---

## Getting started

### Prerequisites

- Linux or macOS with bash and tmux (Windows is not verified)
- Java 21 and Maven
- Python 3.13 with `venv`
- PHP 8.3 with Composer (and `php8.3-fpm` and `nginx` for the production-style web tier)
- PostgreSQL 16 and Redis 7 (Kafka, RabbitMQ, MongoDB and Mailpit are optional; see below)
- Node.js 20 or later only for the browser smoke checks

### First run

The complete, checked procedure (creating the databases, generating keys, the shared secret, seeding sample data) is in [`run.html`](run.html). In outline:

```bash
git clone https://github.com/EnsuretechInsuranceBrokers/Ensuretech.git
cd Ensuretech

# infrastructure (PostgreSQL and Redis are required; the rest are optional)
docker compose -f docker-compose.infra.yml up -d

# start everything that can run, one tmux window per process
scripts/dev-up.sh --no-kafka --with-sheet

# stop it
scripts/dev-down.sh
```

Useful flags for `scripts/dev-up.sh`: `--check` (verify the machine without starting anything), `--bench` (no debugging overhead, raised BFF rate limits, for load tests), `--devtools` (Django Silk and Debug Toolbar), `--eager` (run Celery tasks inline without workers), `--with-mail`, `--skip` and `--only` (choose components). Run it with `--help` for the full list.

Once running:

| Address | What |
|---|---|
| http://localhost:8090/login | Portal (sample users are listed in `run.html`) |
| http://127.0.0.1:8100 | Operations console |
| http://127.0.0.1:5555 | Celery Flower |
| http://localhost:8025 | Mailpit inbox |

### What works without paid services

All local development and testing needs only open-source software on your own machine (see [`docs/runtime-dependencies.md`](docs/runtime-dependencies.md)). Kafka-driven flows and the assistant's generative answers are the two features that need extra components (a Kafka broker; an Anthropic API key); everything else runs without them.

---

## Configuration

Local settings live in [`local.env`](local.env), sourced by every launcher. It holds development-only values and is safe to commit. Values that must not be committed go in an untracked `local.env.private` at the repository root, which `local.env` loads if it exists (it is listed in `.gitignore`).

| Variable | Used by | Meaning |
|---|---|---|
| `GATEWAY_SECRET` / `BFF_GATEWAY_SECRET` | Core services / BFF | The shared signing secret, 32 or more characters; services refuse to start without it |
| `JWT_PUBLIC_KEY_LOCATION`, `BFF_JWT_PUBLIC_KEY_PATH` | Core services / BFF | Identity's public key for verifying tokens |
| `BREAK_GLASS_PASSWORD` | identity-service | Enables break-glass access when 16 or more characters; keep in `local.env.private` |
| `QUERYLOG_ENABLED` | Spring services | Turns the live request and SQL recording on or off (leave off outside development) |
| `BFF_QA_TOOLS` | BFF | Enables QA-only pages and routes; off by default |
| `PIPELINE_AUTO_CONTINUE`, `PARTIAL_AUTO_CONTINUE` | Engine | Whether passed and partly failed uploads continue by themselves |
| `DB_POOL`, `DB_POOL_MAX` | Engine | Database connection pool for the ASGI process |
| `CELERY_BROKER_URL` | Engine | Redis or RabbitMQ |
| `ADMIN_UI_USER`, `ADMIN_UI_PASSWORD`, `FLOWER_BASIC_AUTH`, `METRICS_TOKEN` | Operations tools | Logins and tokens for the operations console, Flower and metrics scraping |

Change every development value before any shared or deployed use.

---

## Testing

| Suite | Location | Command | Approximate count |
|---|---|---|---|
| BFF (API contract, portal, signing, screens for every API) | `services/bff/tests` | `php artisan test` | 108 |
| Identity | `services/identity-service/tests` | `pytest -q` | 62 |
| Endorsement engine | `services/endorsement-service/tests` | `DATABASE_URL=... pytest -q` | 342 |
| Notification | `services/notification-service/tests` | `pytest -q` | 42 |
| Read-service | `services/read-service/tests` | `TEST_MONGO_URI=... pytest -q` (needs MongoDB) | not run in the build environment |
| Spring services | `services/*/src/test` | `DATABASE_URL=jdbc:... mvn test` | policy 101, billing 51, organization 38, integration 56, sheet-generator 66, admin 8 |
| Whole stack through its public doors | `qa/smoke.sh` | with the stack running | 60 checks |
| Browser checks | `qa/portal-smoke.js`, `qa/portal-manage-smoke.js` | with the stack and Playwright | portal pages and every "Settings and tools" area |

The test suites truncate tables. Point `DATABASE_URL` at a scratch database, never at a development database you want to keep. A rule enforced by test: every API endpoint declared in the BFF contract must be served by a portal page or a "Settings and tools" card, so an API cannot be added without a screen.

---

## Repository layout

```
services/
  bff/                     Laravel BFF: portal, public API, contract
  identity-service/        Django: users, roles, tokens, break-glass
  organization-service/    Spring Boot: employers, branches, announcements
  policy-service/          Spring Boot + gRPC: policies, rates, rules
  endorsement-service/     Django + Celery: the endorsement engine
  billing-service/         Spring Boot + gRPC: deposits and ledger
  integration-service/     Spring Boot: TPA integration, offline exchange
  sheet-generator-service/ Spring Boot: sample sheets and generator page
  read-service/            Django + MongoDB: read models, reports, assistant
  notification-service/    Django: event-driven email
  admin-service/           Spring Boot Admin: operations console
shared/contracts/          Event schemas and gRPC definitions shared by services
scripts/                   dev-up, dev-down, bff-fpm, breakglass, jobctl, observability-up
qa/                        smoke.sh, browser checks, load-test harness and results
docs/                      Guides, ADRs, legacy format references
observability/             Prometheus and Grafana configuration
setup.sql, seed.sql        Database creation and sample data
run.html                   The runbook
```

---

## Software used and its documentation

Licenses are those of the upstream projects at the time of writing; confirm the license of the exact version you deploy, particularly for Redis and MongoDB, whose licensing has changed between releases.

### Languages and runtimes

| Software | Role here | License | Documentation |
|---|---|---|---|
| Java 21 (OpenJDK) | Spring services | GPL-2.0 with Classpath Exception | https://docs.oracle.com/en/java/javase/21/ |
| Python 3.13 | Django services and Celery workers | PSF License | https://docs.python.org/3.13/ |
| PHP 8.3 and PHP-FPM | BFF | PHP License 3.01 | https://www.php.net/docs.php |
| Node.js | Browser checks only | MIT | https://nodejs.org/docs/latest/api/ |

### Application frameworks and libraries

| Software | Role here | License | Documentation |
|---|---|---|---|
| Spring Boot 4.1 | Organization, policy, billing, integration, sheet-generator, admin | Apache-2.0 | https://docs.spring.io/spring-boot/ |
| Spring Security | Authentication and authorization in Spring services | Apache-2.0 | https://docs.spring.io/spring-security/reference/ |
| Spring Boot Admin | Operations console | Apache-2.0 | https://docs.spring-boot-admin.com/ |
| Hibernate ORM | Persistence in Spring services | Apache-2.0 (7.x); LGPL-2.1 (earlier releases) | https://hibernate.org/orm/documentation/ |
| Flyway | Database migrations in Spring services | Apache-2.0 (Community) | https://documentation.red-gate.com/flyway |
| Micrometer | Metrics from Spring services | Apache-2.0 | https://docs.micrometer.io/ |
| Resilience4j | Circuit breakers and retries in integration-service | Apache-2.0 | https://resilience4j.readme.io/ |
| gRPC and Protocol Buffers | Policy and billing service-to-service calls | Apache-2.0 / BSD-3-Clause | https://grpc.io/docs/ and https://protobuf.dev/ |
| datasource-proxy | Optional SQL recording for the live request view | Apache-2.0 | https://github.com/jdbc-observations/datasource-proxy |
| Apache POI | Excel reading and writing in Java services | Apache-2.0 | https://poi.apache.org/ |
| Django 6.1 | Identity, endorsement, read and notification services | BSD-3-Clause | https://docs.djangoproject.com/ |
| Django REST framework | JSON APIs in Django services | BSD-3-Clause | https://www.django-rest-framework.org/ |
| djangorestframework-simplejwt | Token issuance and verification | MIT | https://django-rest-framework-simplejwt.readthedocs.io/ |
| Celery 5.6 | Background task execution in the endorsement engine | BSD-3-Clause | https://docs.celeryq.dev/ |
| Uvicorn | ASGI server for the engine (server-sent events) | BSD-3-Clause | https://www.uvicorn.org/ |
| psycopg 3 | PostgreSQL driver and connection pool for Django | LGPL-3.0 | https://www.psycopg.org/psycopg3/docs/ |
| openpyxl | Excel reading and writing in Python | MIT | https://openpyxl.readthedocs.io/ |
| NumPy | Similarity scoring in read-service | BSD-3-Clause | https://numpy.org/doc/ |
| Laravel 13 | The BFF | MIT | https://laravel.com/docs |
| Guzzle | HTTP client used by the BFF for upstream calls | MIT | https://docs.guzzlephp.org/ |

### Data and messaging

| Software | Role here | License | Documentation |
|---|---|---|---|
| PostgreSQL 16 | Primary database, one per service | PostgreSQL License | https://www.postgresql.org/docs/16/ |
| Redis 7 | Cache and default Celery broker | BSD-3-Clause up to 7.2; later releases use different terms | https://redis.io/docs/ |
| Valkey | Redis-compatible alternative under a BSD license | BSD-3-Clause | https://valkey.io/docs/ |
| RabbitMQ 3.13 | Optional Celery broker with dead-letter queues | MPL-2.0 | https://www.rabbitmq.com/docs |
| Apache Kafka 3.9 (KRaft) | Cross-service event streaming | Apache-2.0 | https://kafka.apache.org/documentation/ |
| MongoDB 7 | Read models | SSPL | https://www.mongodb.com/docs/manual/ |
| FerretDB | MongoDB-compatible alternative on PostgreSQL | Apache-2.0 | https://docs.ferretdb.io/ |
| Mailpit | Local mail catcher | MIT | https://mailpit.axllent.org/docs/ |

### Web serving

| Software | Role here | License | Documentation |
|---|---|---|---|
| nginx | Front end for the BFF; TLS termination | BSD-2-Clause | https://nginx.org/en/docs/ |
| PHP-FPM | Runs the BFF under nginx | PHP License 3.01 | https://www.php.net/manual/en/install.fpm.php |

### Observability and operations

| Software | Role here | License | Documentation |
|---|---|---|---|
| Celery Flower | Celery dashboard and control | BSD-3-Clause | https://flower.readthedocs.io/ |
| Django Silk | Request and SQL profiling (development) | MIT | https://github.com/jazzband/django-silk |
| Django Debug Toolbar | Per-page SQL and timing panel (development) | BSD-3-Clause | https://django-debug-toolbar.readthedocs.io/ |
| Laravel Telescope | BFF request and exception inspector (local) | MIT | https://laravel.com/docs/telescope |
| Spatie Ray | Debug output to the Ray desktop app | MIT | https://myray.app/docs |
| Prometheus | Metrics collection | Apache-2.0 | https://prometheus.io/docs/ |
| Grafana OSS | Dashboards | AGPL-3.0 | https://grafana.com/docs/grafana/latest/ |
| Kafka UI | Topic and consumer inspection | Apache-2.0 | https://docs.kafka-ui.provectus.io/ |

### Testing and tooling

| Software | Role here | License | Documentation |
|---|---|---|---|
| pytest and pytest-django | Python test suites | MIT / BSD-3-Clause | https://docs.pytest.org/ and https://pytest-django.readthedocs.io/ |
| JUnit 5 and Spring Test | Java test suites | EPL-2.0 / Apache-2.0 | https://junit.org/junit5/docs/current/user-guide/ |
| PHPUnit | BFF test suite | BSD-3-Clause | https://docs.phpunit.de/ |
| Playwright | Browser checks | Apache-2.0 | https://playwright.dev/docs/intro |
| Docker and Docker Compose | Local infrastructure | Apache-2.0 | https://docs.docker.com/ |
| Apache Maven | Java builds | Apache-2.0 | https://maven.apache.org/guides/ |
| Composer | PHP dependencies | MIT | https://getcomposer.org/doc/ |

---

## Relationship to the legacy system

The legacy system was a Laravel monolith of roughly 25,000 lines in a handful of large files. Its behaviour has been studied closely, and where it encodes real business rules those rules were carried across deliberately rather than rewritten from memory.

- [`docs/legacy/`](docs/legacy/) holds the reverse-engineered sheet formats (Add, Remove and Update, with every column, whether it is mandatory and under what condition), the premium rater definitions, the sum-insured rater sheets, the enrollment report format, and a catalogue of validator defects that were deliberately not copied.
- Result spreadsheets and the header negotiation for uploaded files follow the legacy layouts so existing procedures and files continue to work.
- Behaviour that differs from the legacy system on purpose (for example, a partly failed upload continuing with its valid rows, and error reasons phrased by this system) is stated in [`docs/load-test.md`](docs/load-test.md).

What changed structurally:

| Legacy pattern | Replacement |
|---|---|
| Imports run inside web requests, nested job batches, manual CLI starts | Streaming chunked pipeline on isolated queues, started by the upload |
| Per-row database lookups during validation | Per-chunk validation with set-based checks |
| Authorization checks scattered through controllers | Role and tenant rules in one place per service, from the verified token |
| Credentials inline in integration code | Per-service configuration and secrets outside the code |
| A single database shared by everything | One database per service, events through an outbox |

---

## Known limitations and roadmap

### Known limitations

- Hand testing is under way. Write operations in "Settings and tools", Kafka-driven flows, RabbitMQ, the read-service and the assistant have not been verified end to end.
- The Remove and Update result sheets use the legacy default sample columns because no real legacy result file for them was available.
- The offline template download slows in proportion to the number of members waiting for ids (about 18 seconds at 113,000 waiting members).
- Service-to-service traffic is not encrypted by default, and no independent security assessment has been done.
- Linux is the verified platform. macOS and Windows are not verified.

### Roadmap

Work is ordered by dependency rather than by date.

1. Complete hand testing of every screen and workflow; fix findings.
2. Verify the Kafka event flows, RabbitMQ and the read-service against real brokers and a database.
3. Transport security: HTTPS at nginx, TLS or mutual TLS between services, firewalled gRPC listeners.
4. Independent security assessment and a dependency audit.
5. Measure on production-sized hardware and complete the comparison table in `docs/load-test.md`.
6. Payment service, as described in the access and operations policy.
7. Retrieval quality for the assistant (a real embedding model, document ingestion for PDF and Excel) and a vector index for larger knowledge bases.
8. Staged cutover from the legacy system with both running in parallel.

---

## Contributing

This is an internal repository; contact the engineering team for access.

1. Branch from `main` for each feature or fix. Do not push to `main` directly.
2. Keep each pull request focused, and describe what changed, why, and how it was tested.
3. Before opening a pull request, run the suites for the code you touched (see [Testing](#testing)) and, for changes that affect the running system, `qa/smoke.sh` against a local stack.
4. If you add a BFF API endpoint, add its page or card in `services/bff/config/portal_modules.php`; the coverage test enforces this.
5. If you change request signing, change it in all places together: the BFF (`GatewaySeal.php`), the Spring services (`GatewaySeal.java`) and the Django services (`config/gateway.py`).
6. Database migrations are versioned and are never edited after they have been merged.
7. Update the relevant document in `docs/` when behaviour changes, and add or amend an ADR when an architectural decision changes.

Do not commit secrets. Development values belong in `local.env`; anything sensitive belongs in the untracked `local.env.private` or in your secret manager.

---

## License

Proprietary. All rights reserved. This software is the confidential property of Ensuretech. Unauthorized copying, distribution or use is prohibited. Third-party components remain under their own licenses, listed above.
