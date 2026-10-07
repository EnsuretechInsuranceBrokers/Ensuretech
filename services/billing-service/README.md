# billing-service

Spring Boot 4.1.1 / Java 21. Owns the **client deposit (CD)** that pays for endorsements. Schema `billing` in the shared PostgreSQL.
REST `:8084`, gRPC `:9091`.

## Model
- `cd_accounts`: one per policy, row-locked on every change. `available = balance - reserved`; a forced debit can leave a deficit.
- `cd_ledger`: append-only (DB trigger blocks update/delete). Unique `(account, reference_type, reference_id)` makes every posting idempotent.
- `cd_reservations`: one hold per endorsement job id (`HELD → COMMITTED | RELEASED`).
- `cd_replenishment_requests`: request / approve / reject top-ups (unique reference per account).
- Invariants (asserted in tests): `balance == signed ledger sum`; `reserved == sum of HELD reservations`.

## Flow with the endorsement engine
1. Engine calls gRPC `CheckCdBalance(policy, broker, required, job_id)` (SYSTEM token only). Enough available money → **reserved
   atomically**; re-checks per call, and releases the hold if it can no longer cover. Otherwise returns the deficit.
2. `EndorsementApplied` (Kafka `endorsement.events`) → commit: debit the actual total (negative total = credit), idempotent on `event_id`.
3. `EndorsementStateChanged` to `CANCELLED_BY_HR` / `VALIDATION_FAILED` → release. A TTL sweep releases expired holds.
4. `PolicyApproved` (`policy.events`) → opens the account.
5. Emits `CdBalanceUpdated` / `CdBalanceLow` via the transactional outbox (topic `cd-balance.updates`).

## REST (broker/employer gateways, tenant from the token; cross-tenant ids → 404; HR pinned to own employer)
Accounts, statements, credits (`DEPOSIT|ADJUSTMENT`, idempotent: 201 first / 200 replay), threshold, replenishments. Money is strict
`Digits(14,2)`; we reject rather than round.

## Run / test
`mvn test` with `DATABASE_URL=jdbc:postgresql://host:5432/ensuretech_billing` (35 tests, incl. 40 threads racing one deposit: exactly
the affordable ones win). Needs `JWT_PUBLIC_KEY_LOCATION` (identity public key) at runtime. Contracts: `shared/contracts/`.

## NOT done
- Installments, invoices and statement generation. No debit-adjustment tool.
- Accounts open only via `PolicyApproved` (or implicitly on commit).
- **Kafka consumption has never run against a real broker**: handlers are tested by direct calls with the shared sample events.
- Service tokens: callers use a SYSTEM access token; identity-service has no client-credentials flow yet.
