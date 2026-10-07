# policy-service

Spring Boot 4.1 / Java 21. Owns policies, rate cards, family constructs, the premium engine, and the internal gRPC API
the endorsement engine calls. Schema: `policy_svc`.

## Trust model (same as organization-service)
RS256 access tokens from `identity-service` (public key only; issuer checked; refresh tokens rejected). `broker_id` comes
from the token, never from the request body. Every tenant query includes `broker_id`; another tenant's id is a **404**.
A policy can only attach to an employer of the caller's broker, checked against `policy_svc.employer_refs`, a projection
of `employer.events` (idempotent; an event can never re-parent an employer to another broker).

## REST
| Path (`/api/v1/broker/policies…`) | Roles |
|---|---|
| `POST /` create (starts `NOT_APPROVED`) | BROKER_ADMIN |
| `GET /`, `GET /{id}`, `GET /{id}/readiness` | broker roles |
| `POST /{id}/approve`, `POST /{id}/deactivate` | BROKER_ADMIN (approve requires readiness) |
| `PUT /{id}/rate-card` (validated against the premium type; old card kept as history) | BROKER_ADMIN |
| `PUT /{id}/family-construct` | BROKER_ADMIN |
| `GET /{id}/rate-card`, `/family-construct` | broker roles |
| `POST /{id}/family/validate`, `POST /{id}/premium/quote` | broker roles |
| `GET /lookups/{premium-types,suminsured-subtypes,rater-types,relation-types}` | broker roles |
| `GET /api/v1/employer/policies[/{id}]` | employer roles: LIVE policies of their own employer only |

Statuses: `NOT_APPROVED → APPROVED_AND_LIVE → INACTIVE`; a nightly sweep moves past-due live policies to `EXPIRED`.
Domain failures are 422 with machine-readable `issues`; bad shape is 400.

## gRPC (`shared/contracts/grpc/policy_validation.proto`, port 9090)
`ValidatePolicyConfig`, `GetRateCard` (the active card is found by a tiny tenant-checked DB lookup; its immutable body is cached in Redis by card id, 1 h TTL, so a replaced card is never read stale and no eviction ordering exists to get wrong),
`CalculatePremium`, `GetPolicy` and batch `ValidateFamilies` (additions to the original contract: the engine and family rules live here, so callers ask rather than re-implement them; each family issue carries the offending `member_index`).
Send `authorization: Bearer <jwt>` metadata. Broker tokens act on their own tenant (the request's `broker_id` is ignored);
`SYSTEM` service tokens must name `broker_id`; employer/TPA tokens are rejected. Plaintext for now, TLS/mTLS comes with deployment.

## Events
Emits `policy.events` (key = `broker_id`): `PolicyCreated/Approved/Deactivated/Expired`, `RateCardChanged`, via the shared
transactional outbox. Consumes `employer.events`. Delivery is at-least-once; dedupe on `event_id`.

## Rating rules: READ THIS
The legacy rater source is not in the repo, so each `rater_class` below has an **explicit rule I defined**, not one ported
from legacy. They are covered by tests (all 32 premium types, boundary ages) but **need sign-off against the legacy raters**
before production. Premiums are **annual and pre-tax**; the caller applies pro-rata. Tax comes from the rate card (default 18%).

| rater_class | `rate_table` | Rule |
|---|---|---|
| FlatRater, ProRataRater | `flat` (or `family_definitions` keyed by dependent count) | per member; family-unit types (floaters, `FLAT_EMPLOYEE_*`) charge once on the employee; `EMPLOYEE_ONLY/+1/+2` cap dependents |
| AgeRater, ProRataAgeRater | `age_bands[{from,to,premium}]` | inclusive bounds, no overlaps; family-unit types use the **eldest** member (assumption) |
| GDACRater | `gdac_bands` | same band lookup per member (assumption: GDAC = age-banded) |
| GradeRater | `grades{}` | dependents inherit the employee's grade |
| DesignationRater | `designations{}` | dependents inherit the employee's designation |
| MatrixRater | `grade_age_matrix` / `designation_age_matrix` | grade (or designation) then age band |
| LOLRater | `lol[{limit,premium}]` | sum insured must equal a limit exactly |
| PermillyRater | `permille` | sum insured × permille / 1000 |
| FlexRater | `layers{base,enhance,flex}` | sum of the member's selected layers (default `base`) |
| BaseTopUpRater / BundleRater | `base`+`topup` / `gmc`+`wellness` | both always apply |
| OpdFlatRater / OpdGradeRater | `opd_flat` / `opd_grades{}` | per member |
| CompositeRater, ConstructRater | `sections[{rater,table}]` | sum of non-nested sections (assumption for ConstructRater) |

Family construct rules (assumptions): `allowed_relations` is authoritative; saving is rejected if it contradicts
`include_parents`/`include_parents_in_law`; parents and parents-in-law both count toward `max_parents`; `max_members` includes the employee.

## Relation rules, pro-rata and the sheet spec
- `pro_rata_type` is DAILY | MONTHLY | NONE (renamed from EXACT_DAYS / FULL_MONTH to the legacy names; NONE = pro-rata not allowed).
- `PUT/GET /policies/{id}/relation-rules` (the legacy `policy_age_details`): per relation `minAge`, `maxAge`, `ageDifference` + `differenceFromRelation` (SELF or SPOUSE),
  `proRataType` (inherits the policy's; the policy's NONE wins), `maxTwins`. Replaced atomically. Enforced by `ValidateFamilies`: `RELATION_AGE_BELOW_MIN`, `RELATION_AGE_ABOVE_MAX`,
  `AGE_GAP_TOO_SMALL` (whole-year ages, so +-1 year against the legacy date-based check), and `ADULT_UNDER_18` for self, spouse, parents and in-laws when no minimum is set.
  `maxTwins` is enforced as `TWINS_EXCEEDED` when validation receives dates of birth (the engine sends them; REST `dob` is optional): children sharing a birth date are twins; the limit is the smallest configured `maxTwins` among them (0 counts as 1). If none of the twins' relations has a rule row, twins are **not** checked (assumption: legacy only limited them when configured; confirm).
- `GET /policies/{id}/sheet-spec` (`sheet-spec/v1`): the policy's premium type, valid grades / designations / age bands / LOL limits taken from the active rate card, allowed relations and limits,
  relation rules with the resolved pro-rata kind, and what an upload sheet must carry. Missing configuration is reported under `gaps`, never guessed. `GetPolicy` (gRPC) carries `relation_rules`.

## Run / test
```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/ensuretech   # Flyway creates `policy_svc`
export REDIS_HOST=localhost KAFKA_BOOTSTRAP_SERVERS=localhost:9092
mvn spring-boot:run
mvn test   # needs PostgreSQL (DATABASE_URL) and Redis (REDIS_PORT); truncates policy_svc.* and flushes that Redis DB
```
The outbox/security code is duplicated from organization-service; extracting a shared library is a known follow-up.

## Contribution plan (employee / employer split)
`PUT|GET /api/v1/broker/policies/{id}/contributions` (PUT: BROKER_ADMIN) `{ "type": "NONE|PERCENT|FIXED", "groups": {"SELF": 0, "SPOUSE": 50, "CHILD": 50, "PARENT_SINGLE": 100, ...} }`,
replaced as a whole. Groups: `SELF, SPOUSE, CHILD, PARENT_SINGLE, PARENT_DOUBLE, PARENT_IN_LAW_SINGLE, PARENT_IN_LAW_DOUBLE, OTHERS` (legacy `*_employee_contribution`).
PERCENT: the employee pays that % (0-100) of each member's premium; FIXED: that amount per member, never above the premium; the employer pays the rest. Carried to the
engine on `GetPolicy` (`contribution_type`, `contribution_groups`). No plan = `NONE` = the split is **not computed** (not "employer pays").
**Assumption to confirm:** legacy's `calculatePremiumWithContribution` was not in the files I was given; I implemented the rule visible in its callers (fixed: `employee = min(premium, contribution)`, employer = remainder;
percent: employee = premium x %). Fixed amounts are *not* pro-rated separately: the split follows each member's pro-rated charge. Legacy has separate OPD contribution types (`premium_contribution_type_opd`) - not ported.
