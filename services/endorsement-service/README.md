# endorsement-service

Django 6.1 + DRF + Celery. The engine that takes an endorsement (add / remove / update members of a policy) from raw input
to the live roster: intake -> chunked validation -> premium -> deposit check -> endorse, with a state machine, an immutable
audit trail and an outbox. It asks **policy-service** (gRPC) for family rules and pricing instead of re-implementing them.

```
 CSV upload ─┐                                              ┌─ policy-service (gRPC: GetPolicy, ValidateFamilies,
 GUI session ┴─> EndorsementJob ─> validate chunks ─> price chunks ─> CD check ─> apply chunks ─> ENDORSED
 (typed jobs)     REQUEST_RAISED     (pandas, 1 RPC/chunk)  (1 RPC/chunk)  (billing)   (bulk, idempotent)   └─ CalculatePremiumBatch)
```

## Guarantees (each is covered by tests)
- **Tenancy:** broker/employer scope comes only from the verified RS256 token (HS256, refresh tokens, wrong issuer rejected). Every job,
  session, mapping and report query is scoped; another tenant's id is a **404**.
- **State machine:** the only code that changes a job's status. Illegal moves and moves out of terminal states are refused; each change
  writes an audit row and an outbox event in one transaction. The audit table is append-only **at the database level** (trigger).
- **Safe redelivery:** every chunk is claimed with a compare-and-set, attempts are counted in the database, a crashed worker's stale chunk
  is reclaimable, roster writes are guarded by applied-row markers. Replaying any task changes nothing.
- **Nothing half-applied reaches the roster:** failing rows never get priced or applied; a partial file waits for a human (`/proceed/`);
  roster conflicts (someone else changed the member) stop the job instead of corrupting data.
- **Money is exact:** `Decimal` end to end, strings over the API, pro-rata and tax rounded half-up.

## API (`/api/v1/broker/endorsements/` and `/api/v1/employer/endorsements/`)
| | |
|---|---|
| `POST csv/preview/` · `GET/POST csv/mappings/` · `PUT csv/mappings/{id}/` | header negotiation (Levenshtein + synonyms + confidence), reusable mappings |
| `POST csv/upload/` (`Idempotency-Key`) | 202 + `progress_url`; policy must be live and in the caller's tenant |
| `POST gui/sessions/` · `POST gui/sessions/{id}/rows/validate/` · `PUT/DELETE gui/sessions/{id}/rows/{key}/` | live per-row validation (rate-limited), staging |
| `POST gui/sessions/{id}/submit/` · `.../abandon/` | server-side split into one typed job per operation |
| `GET /`, `GET {job}/`, `GET {job}/errors/`, `GET {job}/audit/` | list, detail, row-precise error report, audit trail |
| `GET {job}/progress/` | Server-Sent Events (needs an ASGI server; header auth) |
| `POST {job}/proceed/` · `POST {job}/retry/` · `POST {job}/cancel/` | human decisions |

## State machine
`REQUEST_RAISED → FILE_STREAMED → VALIDATION_QUEUED → VALIDATION_IN_PROGRESS → VALIDATION_PASSED | VALIDATION_PARTIAL | VALIDATION_FAILED`
`→ PREMIUM_CALC_QUEUED → PREMIUM_CALCULATED → CD_CHECK_INITIATED → CD_SUFFICIENT → ENDORSEMENT_QUEUED → ENDORSEMENT_IN_PROGRESS → ENDORSED`
Blocked/failed states are recoverable (`CD_INSUFFICIENT`, `CD_PARTIAL_SUFFICIENT`, `CD_CHECK_FAILED`, `PREMIUM_CALC_FAILED`,
`ENDORSEMENT_FAILED`, `MANUAL_INTERVENTION_REQUIRED`) via `/retry/`; `CANCELLED_BY_HR` is allowed until execution is queued.
`TPA_SYNC_*` and `COMPLETED` are defined for integration-service to drive; nothing here drives them yet.

## Performance (measured, one process, fake policy/billing, 30k rows / 10k families)
Upload+ingest ~7 s · validation 6.3 s · pricing 2.9 s · apply 6.5 s -> **~1,340 rows/s end to end**. Validate/price/apply are per-chunk
tasks, so they scale with workers. Real gRPC to policy-service: `CalculatePremium` ~5-9 ms per call vs `CalculatePremiumBatch`
~0.07 ms per family (~70x), `ValidateFamilies` ~0.07 ms per family; the engine uses the batch calls.
**Not met: the README's "1M rows in 22 s".** Ingest is a single serial stream (~4-5k rows/s, so ~4 min per million); splitting the file
across workers is the next lever. Nothing here has been benchmarked at 1M rows, with real RabbitMQ, or across multiple workers.

## What is NOT done (read before relying on it)
- **Test-data / Faker intake path** (third path) and the `broker_id=0` isolation machinery: not built. `source_type` has the values.
- **WebSocket live-validation** channel: not built (the REST validate endpoint serves the same checks).
- **Billing / CD:** the default client is now `GrpcCdClient` (billing-service `CheckCdBalance`, 500 ms deadline, SYSTEM token). The check
  *reserves* the job's total atomically (one hold per job id); billing commits it on `EndorsementApplied` and releases it on cancel /
  validation-failed. If billing is down the job parks at `CD_CHECK_FAILED` (never silently passes). Verified against the real service
  by `scripts/e2e_with_policy_service.py` with `E2E_BILLING_URL`; the Kafka leg (commit/release events) is only contract-tested.
- **Service credential:** set `IDENTITY_TOKEN_URL` + `SERVICE_CLIENT_ID/SECRET` and the engine fetches its own SYSTEM token from identity-service
  (client-credentials; cached, refreshed 60 s before expiry, dropped on UNAUTHENTICATED). Unset, it falls back to the static
  `POLICY_GRPC_TOKEN`/`BILLING_GRPC_TOKEN`. One identity currently grants SYSTEM on every service (no per-service scopes).
- **File store:** uploads spool to local disk (`LocalFileStore`); API and workers must share it. A MinIO/S3 store fits the same interface.
- **Messaging not exercised for real:** RabbitMQ queue topology/DLX is configured but tests run Celery eagerly; the Kafka relay is tested
  with a fake sender. Redis is only used for the shared rate-limit cache when `REDIS_URL` is set.
- **Pro-rata (ported from the legacy helpers):** kinds DAILY / MONTHLY (NONE = not allowed), set per policy and overridable **per relation** (policy-service `relation-rules`).
  Joiners/changes pay `covered/total` (DAILY: inclusive days; MONTHLY: ceil of fractional months, both numerator and denominator), full premium from the policy start.
  Removals refund the part of the year after the leaving date (the leaving day stays covered; `REMOVAL_INCLUDES_LEAVING_DAY=false` is the TATA AIG same-day variant).
  **Decision needing confirmation:** the legacy refund function has no "pro-rata allowed" check, so on a NONE policy it refunds daily; `REMOVAL_REFUND_WHEN_PRORATA_DISABLED` = DAILY (legacy) | FULL | NONE.
  Not ported: the enrollment-window grace (`isProRataCalculation`: no pro-rata inside the window or within `enrollment_days` of joining/upload), QUARTERLY/HALF_YEARLY (never implemented in legacy), contribution split, rounding unit.
- **Assumptions to confirm against the legacy system:** the cell-normalisation rules
  (`apps/intake/rules/normalization_rules.json` is authored, with per-TPA relation labels taken from the DB design), the family-issue
  attribution, and which validation findings are warnings (mobile, email, dependents-remain) vs errors.
- **Pricing a partial family** prices the whole resulting family before/after, so floaters and composition-sensitive plans charge the
  right difference, but the deposit requirement is `max(total, 0)` per job, not per family.

## Run
```bash
# PostgreSQL must be UTF-8 (HR files contain names and ₹). The service creates/uses the `endorsement` schema.
export DATABASE_URL=postgres://user:pass@localhost:5432/ensuretech JWT_PUBLIC_KEY_PATH=../identity-service/keys/jwt-public.pem
python manage.py genproto && python manage.py migrate       # generates gRPC stubs from shared/contracts/grpc
uvicorn config.asgi:application --port 8083                  # ASGI: required for SSE
celery -A config worker -Q csv.ingestion,validation.standard,validation.high,computation -c 4 --prefetch-multiplier=1
celery -A config worker -Q maintenance -B                    # outbox relay + GUI session expiry
pytest                                                        # needs PostgreSQL; creates a throwaway UTF-8 test database
python scripts/e2e_with_policy_service.py                    # real policy-service + real identity tokens (see its docstring)
```

## Employee / employer split
When the policy has a contribution plan (policy-service `/contributions`), each `PremiumLine` carries `employee_charged` and `employer_charged` (sum = `charged`, before tax; tax is on the total).
Computed per member as the change in the employee's share between "family today" and "family after this endorsement", pro-rated with that member's own factor, so a second parent
turning "single" into "double" contributions re-prices the first parent's share too. No plan -> both are `NULL` (unknown). Not yet aggregated onto the job or sent in events.
