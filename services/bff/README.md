# bff (the orchestrator)

Laravel 13 / PHP 8.3. The public front door of the platform, as described in the root README ("BFF as the Public Contract Boundary"). It **owns the public API
contract and holds no business logic and no database**: every authorisation decision, calculation and piece of state stays in the services behind it.

```
client --JWT--> BFF --(same JWT, X-Correlation-Id)--> identity / organization / policy / endorsement / billing / integration / read
```

## What it does
| | |
|---|---|
| **Contract, not a proxy** | `config/contract.php` is the only list of public endpoints and fields. Routes are generated from it; there is no catch-all. A service can add a field or an endpoint tomorrow and nothing leaks until the contract says so. An endpoint with no response schema refuses to boot. |
| **Normalisation** | One public vocabulary: snake_case, pages are `{items, total, page (1-based), page_size}` whatever the backend dialect (Spring 0-based `content/size` vs Django `results/page_size`), jobs use `status`, internal ids (`broker_id`, `status_code`, `correlation_id`...) never appear. |
| **Authentication** | Verifies identity-service's RS256 access tokens (algorithm pinned, issuer, expiry, `token_type=access`); forwards the same token so every service verifies it again. Callers stay on their own gateway (`/broker` vs `/employer`); SYSTEM and TPA tokens are refused. |
| **Sanitised input** | Query parameters and JSON body keys are allowlisted per endpoint (scalars only); path parameters are regex-constrained (`../` cannot get through); only `Authorization`, `Accept-Language` and declared headers (e.g. `Idempotency-Key`) travel on. Uploads are streamed through with a size cap. |
| **Sanitised output** | Upstream errors are mapped to one RFC 7807 shape with a correlation id; 5xx become a generic 502, timeouts a 504; hosts, stack traces and raw bodies are never shown. A service answer that does not match the contract is a 502, not a leak. Redirects are not followed. |
| **Composition** | `GET /api/v1/{broker,employer}/policies/{id}/overview` fetches the policy, deposit account, recent endorsements and (brokers) TPA syncs in parallel. The policy is required; other parts degrade independently and are listed under `degraded`. |
| **Progress stream** | `GET .../endorsements/{id}/progress` re-emits the engine's Server-Sent Events event by event through the same allowlist. |
| **Mitigations** | `storage/bff/mitigations.json` (see the example): id, endpoint, reason, owner, introduced, **expires**, removal condition and the permanent fix are all mandatory; an incomplete entry is refused, an expired one stops applying by itself. Only `rename`, `map`, `drop` and `default` exist: no arithmetic, because recalculating a premium here would be business logic. Applied ids come back in `X-Mitigations`. |
| **Limits** | Per-IP before authentication, then per tenant and per user, plus a stricter bucket for uploads (`bff.limit`, not Laravel's `throttle`, which the framework sorts *before* authentication and would key everything by IP). |
| **Honeypots** | Bait paths (`/api/v1/admin/export`, `/debug/config`, ...) answer like any unknown path and write a critical entry to the `security` log channel (the silent alert). |
| **Ops** | `GET /api/v1/ops/mitigations`, `/ops/upstreams` for roles listed in `BFF_OPS_ROLES`. identity-service does not issue such a role yet, so the endpoints are closed until it does. |

## Run / test
```bash
composer install && cp .env.example .env && php artisan key:generate
php artisan serve --port 8090
php artisan test            # 54 tests, no database or network: upstreams are faked
```
Needs the identity public key (`BFF_JWT_PUBLIC_KEY_PATH` or `BFF_JWT_PUBLIC_KEY`) and the `UPSTREAM_*` URLs (defaults match each service's default port; read-service on 8087 is a guess).
Use the `redis` cache store when more than one instance runs, so rate-limit counters are shared.

## The frontend's only door
The contract now covers the whole product surface a broker or HR screen needs, ~90 endpoints x gateways: sign-in/refresh/logout/me/change-password and user creation;
employers (create, edit, deactivate, parent/child, config), branches; the full policy lifecycle (create, approve, deactivate, readiness, rate card, family construct,
relation rules, contributions, rounding, mid-term, mid-term dependants, family validation, premium quote); deposits (accounts, statements, credits, thresholds, pools,
replenishments); TPA configuration, sync jobs, reconciliation; endorsement intake (CSV upload and preview, column mappings, row-level errors, GUI sessions, progress stream,
cancel/proceed/retry/close); dashboards, activity, downloadable reports; the assistant (ask, knowledge). Test-data generation (`/sheets/*`) exists only when `BFF_QA_TOOLS=true`.
Request bodies are validated and renamed by `App\Bff\Binder` from the contract's `inputs`; files and reports stream through with a type allowlist and size cap.

**What the BFF cannot do by itself:** make it the *only* door. That is a deployment property: publish only the BFF's port, keep every service on a private network
(and mTLS between them), and let nothing else resolve to them. The services still verify every token, so a leaked port is not an open door, but the isolation must be real.

## Not done / assumptions (read before relying on it)
- **Never run against the real services.** Contract shapes were taken from reading each service's code (`PolicyResponse`, `AccountResponse`, `job_to_dict`, the read-service listing...); the tests use fakes. Spring `Page` JSON is assumed to have `content/totalElements/number/size` at the top level. Run it once against the running stack before trusting a field.
- **No mTLS.** The README requires service-to-service mTLS; that belongs to the deployment (reverse proxy / mesh) and is not configured here.
- **Payment Service bypass** is documented in the root README and intentionally has no route here.
- Not in the contract (the services have them, the frontend has no need yet or it needs a decision): admin broker creation (SYSTEM only), service-token issue, the read-service's own policy listing (policy-service serves policies), notification-service, knowledge-document detail, GUI row listing beyond the session view, CORS preflight for PUT/PATCH/DELETE (only GET/POST/OPTIONS are allowed cross-origin by default: widen `config/cors.php` for the portal).
- No GraphQL or WebSocket (the README mentions them for clients). CORS is closed by default: set `BFF_CORS_ORIGINS` to the portal origin(s).
- Anomaly detection beyond honeypots and rate limits is not built.
