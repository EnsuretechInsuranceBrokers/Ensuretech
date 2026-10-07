# organization-service

Spring Boot 4.1 / Java 21. Owns brokers, employers, branches, per-employer config and the transactional outbox.

## Trust model
- Validates RS256 JWTs from `identity-service` with its **public key only** (`JWT_PUBLIC_KEY_LOCATION`).
  Issuer must match, and `token_type` must be `access` (refresh tokens are rejected).
- `JwtTenantAuthenticationFilter` copies `broker_id` / `employer_id` / `role` into a ThreadLocal `TenantContext`
  once per request and clears it after. Tenant scope is **never** read from request bodies or parameters.
- Every tenant query includes `broker_id`; a cross-tenant id returns 404, not 403.
- Gateways: `/api/v1/broker/**` (broker roles), `/api/v1/employer/**` (employer roles), `/api/v1/admin/**` (SYSTEM).
  Everything else is denied.

## API (so far)
| Method & path | Roles |
|---|---|
| `POST/GET/PATCH/DELETE /api/v1/broker/employers[/{id}]` | write: BROKER_ADMIN, read: broker roles (hidden employers: admin only) |
| `GET/PUT /api/v1/broker/employers/{id}/configs[/{key}]` | write: BROKER_ADMIN |
| `GET/POST /api/v1/broker/branches` | write: BROKER_ADMIN |
| `GET /api/v1/employer/employer` | employer roles (their own employer only) |
| `POST/GET /api/v1/admin/brokers` | SYSTEM |

## Outbox
Writes happen in the caller's transaction (`Propagation.MANDATORY`). `OutboxRelayJob` publishes to Kafka
(`employer.events`, key = `broker_id`) using `FOR UPDATE SKIP LOCKED`, with backoff and dead-lettering after
`max_retries`. Delivery is at-least-once: consumers dedupe on `event_id`.

## Run / test
```bash
# needs PostgreSQL; Flyway creates the `organization` schema
export DATABASE_URL=jdbc:postgresql://localhost:5432/ensuretech
mvn spring-boot:run
mvn test      # integration tests run against the real database in DATABASE_URL (they TRUNCATE organization.*)
```
