# Event contract samples

Each `*.sample.json` is the shape one service publishes and another consumes. Both sides test against these files:
the producer asserts its real event has exactly these keys, the consumer feeds the file to its real handler.
Changing a field means changing the sample, which fails whichever side forgot to follow.

| Sample | Producer | Consumer |
|---|---|---|
| `endorsement-applied` | endorsement-service (`endorsement.events`) | billing-service (commits the deposit reservation) |
| `endorsement-state-changed` | endorsement-service | billing-service (releases the reservation on cancel/failed validation) |
| `policy-approved` | policy-service (`policy.events`) | billing-service (opens the deposit account), integration-service (routes the policy to its TPA via `tpa_id`, nullable) |
| `endorsement-applied` (second consumer) | endorsement-service | integration-service (creates the TPA sync job; fetches members from the engine's internal API) |
| `tpa-sync-completed` | integration-service (`tpa.events`) | none yet (insurer reports, notifications) |
| `tpa-sync-failed` | integration-service (`tpa.events`) | none yet (alerting); a dead-lettered sync waits for an operator retry |
| all event samples above | various | read-service (projects them into MongoDB read models and RAG history chunks) |
| `cd-balance-updated` | billing-service (`cd-balance.updates`) | read-service |
| state-changed / applied / policy-approved / cd-balance (`CdBalanceLow` carries the same fields as `cd-balance-updated`) / tpa-sync-failed | producers above | notification-service (mail triggers) |

`CdBalanceUpdated`/`CdBalanceLow` also carry `account_id` and `account_type` (`POLICY`, `GROUP`, `COMPANY`, `MULTIPLE`). For a shared account `policy_id` is its lowest member and the optional `policy_ids` lists every member (up to 1000); consumers that key balances by policy must update each of them.
