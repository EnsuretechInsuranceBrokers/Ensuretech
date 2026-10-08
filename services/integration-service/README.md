# integration-service

Spring Boot 4.1.1 / Java 21. Pushes endorsed members to the TPA. Schema `integration`; REST `:8085`. No gRPC.

## Flow
1. `PolicyApproved` (`policy.events`, now carries `tpa_id`) → `policy_routes` (policy → TPA).
2. `EndorsementApplied` (`endorsement.events`) → one `sync_jobs` row (unique `event_id`, so redelivery is harmless). No TPA on the policy, or
   0 members changed → `SKIPPED`.
3. Workers claim due jobs (`FOR UPDATE SKIP LOCKED` + a lease, so a crashed worker's job is reclaimed) and page through the endorsement's roster rows
   using the engine's SYSTEM-only internal API (`GET /api/v1/internal/endorsements/{job}/members/`, keyset paged), sending each batch to the TPA
   through `TpaGateway`. After every batch the **cursor** is saved, the TPA's member ids are written back to the roster
   (`POST .../tpa-member-ids/`) and a retry resumes from the cursor. A batch in flight is resent with the same `Idempotency-Key`.
4. Done → `SUCCEEDED` + `TpaSyncCompleted`; dead-lettered → `DEAD` + `TpaSyncFailed` (topic `tpa.events`, via the outbox).

## Resilience
- One circuit breaker per (broker, TPA); only retryable failures count. An open circuit reschedules the job **without** consuming an attempt.
- In-call retry (3 tries, 1s/2s/4s), then job-level exponential backoff (30 s base, 1 h cap, ±20 % jitter), `max-attempts` 8, then `DEAD`.
- Permanent failures (HTTP 4xx, bad response, missing config or credentials) go `DEAD` immediately. Operators fix and `POST /sync-jobs/{id}/retry`.
- Members the TPA rejects are stored in `sync_rejections` and do not fail the job.

## Security
- TPA credentials are never stored: a config names an env-var prefix (`secretRef`; `<REF>_USERNAME/_PASSWORD` or `<REF>_TOKEN`) injected by the deployment.
- SSRF guard (`UrlGuard`): https only, no embedded credentials, no loopback/private/link-local/CGNAT/ULA targets, no redirects; checked on save and before
  each run. A DNS-rebinding race remains, so also egress-filter this service.
- Broker APIs (`/api/v1/broker/integration/...`): tenant from the token, other brokers' rows are 404, writes need BROKER_ADMIN, employers get 403.
- The engine token comes from identity's client-credentials flow (`SERVICE_CLIENT_ID/SECRET`).

## API
`GET/POST/PUT tpa-configs`, `GET sync-jobs[?status|policyId]`, `GET sync-jobs/{id}` (with rejections), `POST sync-jobs/{id}/retry`, `GET status` (breaker states, counts).

## Run / test
`DATABASE_URL=jdbc:postgresql://host:5432/ensuretech_integration mvn test` (19 tests; real HTTP against in-process fake TPA/engine/identity servers).
Also run once for real: Java worker → identity token → Django internal API → a fake TPA → ids written back to the roster (25/25).

## NOT done (read before relying on it)
- **Only a generic adapter exists.** `GenericRestAdapter` speaks *our own* JSON protocol. The real Care / MediAssist / FHPL / Paramount / Palm-Vipul /
  Safeway formats (token generation, `careEncrypt/Decrypt`, ...) are in the legacy code that is not in this repo: each needs its own `TpaAdapter`.
- **HRMS, wellness-SSO, insurer-report adapters and the e-card consumer** from the design are not built; neither is `TpaMemberNormalizer`.
- Kafka consumption has never run against a real broker (handlers are tested by direct calls with the shared sample events).
- A TPA-side change to a policy after approval is not tracked (no policy-updated event consumed); the route comes from `PolicyApproved` only.
- Roster rows are fetched by "last job to touch them": a later endorsement changing the same person supersedes the earlier job's row.
- Only ADD/UPDATE/REMOVE pass-through; TPA-specific operation mapping belongs in the adapters.

## Pull and reconcile (legacy GetEnrolmentData / assignTpaMemberId)
`POST /api/v1/broker/integration/policies/{policyId}/reconcile` (BROKER_ADMIN) reads the policy's whole active roster from the engine, asks the policy's TPA which members it covers
(`GET {baseUrl}/active-lives?policy_number=P` on the generic adapter), matches them (`tpa/Reconciler`) and writes each TPA member id back to the engine. Matching: employee code
trimmed and case-insensitive (optionally ignoring leading zeros, per TPA), the TPA's relation label translated through a per-TPA mapping (built-in defaults + overrides), spouse covers
husband/wife, children must also match by name (full name, or first name as a whole word), one TPA record claims one member and vice versa. Anything unplaceable is a stored miss with a reason
(`GET /reconcile-runs/{id}`), never skipped silently. `PUT /tpa-configs/{id}/reconciliation` sets `stripLeadingZeros` and `relationLabels`. Runs are synchronous, capped at 200,000 members.
Scheduler: `app.reconcile.enabled` (env `RECONCILE_ENABLED`, off by default) + `cron` (default every 6 h) reconciles at most `batch` policies per tick, stalest first, none more often than `min-interval-seconds`; failed runs go to the back of the queue.
Not done: the real Care/MediAssist/FHPL/Paramount response formats (only our generic JSON shape), the ICICI deleted/added status flags, and the nested "dependentList" variants beyond the generic `dependents` array.
