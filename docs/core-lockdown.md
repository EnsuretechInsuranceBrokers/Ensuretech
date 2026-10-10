# The core services answer only the BFF

Nobody can `curl` or use Postman against a core service (identity, organization, policy, billing, endorsement engine, integration, sheet-generator,
read, notification), with a stolen user token or without one. The only callers are the BFF, other core services, and a holder of super credentials.

## The rule

Every request to a core service must have **one** of these, checked before authentication, parsing or any database work. Without one the answer is an empty `403`.

1. **The BFF's signature.** Every call the BFF makes carries
   `X-Gateway-Timestamp`, `X-Gateway-Nonce` and `X-Gateway-Signature = HMAC-SHA256(secret, "v1\n" ts "\n" nonce "\n" METHOD "\n" path "\n" query "\n" sha256(Authorization header))`.
   A signature is good for one method, path, query and user token, for 60 seconds, once (a replay is refused by a nonce cache in each service; a full cache refuses
   rather than forgets). The secret never travels, so capturing a request does not let anyone send a different one.
   Code: `bff/app/Bff/GatewaySeal.php` (registered as a global HTTP middleware, so no call site can forget), `*/security/GatewaySeal.java`, `*/config/gateway.py`.
2. **A verified SYSTEM token** (super credentials). A service account's `client_id` and 384-bit secret are stored in identity-service as a SHA-256 hash (never in plain
   text); signing in at `POST /api/v1/auth/service-token/` returns a short-lived RS256 token with role `SYSTEM`. This is how the engine and integration-service call other services,
   and how an operator reaches a core service from their own machine. Create or rotate one with `manage.py service_account`. A SYSTEM token has no tenant, so it
   cannot act as a broker or employer on the browser-facing APIs; it reaches the internal endpoints and identity's admin APIs.

Only `/actuator/health` (Spring, for the load balancer) and identity's `/api/v1/auth/service-token/` (the sign-in for super credentials) are open. Set `GATEWAY_OPEN_CIDRS`
on identity to narrow who may even knock on the second.

## Fail closed

- A core service **refuses to start** without `GATEWAY_SECRET` (32+ characters). `GATEWAY_ALLOW_UNGUARDED=true` is the explicit local-development opt-out; the Django test run and the Spring test suites set it.
- The BFF refuses to boot outside `local`/`testing` with a short `BFF_GATEWAY_SECRET`.
- gRPC (policy and billing): service accounts only (`app.grpc.system-only`, default true). Nothing a person holds gets in.
- Django admin (`/django-admin/`) is behind the same rule, so it is closed to browsers; reach it from a private network with an operator proxy if you need it.

## Controls: a second secret for the dangerous ones

Reading how the services are doing and changing how they behave are different powers. `/actuator/loggers`, `threaddump`, `scheduledtasks`, `metrics` and `/api/v1/ops/**` answer only a SYSTEM token. On top of that, stopping or requeuing a background job needs the **job-killer password**, a second secret that exists only as a bcrypt hash in the database,
audited on every use and locked out after five wrong guesses (`docs/observability.md`). So a leaked service-account secret alone cannot kill jobs, and a leaked job-killer password alone cannot reach the endpoint.

## Network (the layer code cannot replace)

- Spring services listen on `BIND_ADDRESS` (default `127.0.0.1`); set it to the private interface the BFF reaches them on. Run Django behind gunicorn bound the same way.
- Put the core services, PostgreSQL, Redis, MongoDB and Kafka on a private network with a security group that admits only the BFF's address (and the services to each other).
  `docker-compose.infra.yml` publishes its ports on `127.0.0.1` only.
- Only the BFF faces the internet, behind a CDN/WAF for volumetric DDoS. Per-user and per-tenant rate limits, upload size caps and timeouts are in the BFF; the
  cores add header-size and swallow-size caps, a Tomcat thread cap and connection timeouts. Nothing in application code can absorb a network flood: that is the CDN's and the firewall's job.

## Injection

All SQL is parameterised: JPA/Spring Data (three `nativeQuery` statements, all with bound parameters), `JdbcClient` with named parameters, and the Django ORM. The two places that
assemble SQL text (the engine's batched `UPDATE ... FROM VALUES`, and the integration history query) interpolate only a quoted table name, placeholder markers and fixed
fragments; every value is bound. Request fields are validated at the BFF against a contract with an allow-list of fields before they get that far.

## What this does not do

- It does not defend against someone who has the shared `GATEWAY_SECRET` or a SYSTEM service-account secret: those are the keys. Keep them in a secret manager, rotate on a schedule, and never in the repo (`local.env` holds development values).
- The nonce cache is per process: with several instances a captured request could be replayed once per instance inside its 60-second window. The signature still pins its method, path, query and token.
- A request that gets past the guard still needs a valid user token and passes the services' own tenant checks.
