# sheet-generator-service

Generates **ADD, UPDATE and REMOVE** endorsement sheets (`.xlsx` / `.csv`) that satisfy one policy's rules, and ships a **standalone HTML tool**
(`src/main/resources/static/index.html`, served at `/`) that does the same offline from a saved `sheet-spec/v1` file.

It replaces the old browser-only "Add-Sheet Generator" (see `docs/legacy/addsheet-generator-review.md` for what was wrong with it).
The difference: the sheet is driven by the policy (`GET /api/v1/broker/policies/{id}/sheet-spec` in policy-service), not by free-text settings, so
generated data cannot drift from what the policy accepts.

Spring Boot 4.1.1 / Java 21, port **8086**, schema `sheetgen` (Flyway), Apache POI (streamed `SXSSF`) for `.xlsx`.

## API (broker roles only; employer and SYSTEM tokens get 403)
| Call | What it does |
|---|---|
| `GET  /api/v1/broker/sheets/spec?policyId=` | the policy's sheet-spec, fetched from policy-service **with the caller's own token** (a policy of another broker is a 404) |
| `POST /api/v1/broker/sheets/generate` | JSON: columns, rows keyed by header, warnings, the code block used, and `issues` (the output re-checked by the validator; non-empty means a generator bug) |
| `POST /api/v1/broker/sheets/generate/file?format=xlsx\|csv` | the same sheet as a download |
| `POST /api/v1/broker/sheets/validate` | any sheet (rows keyed by header) checked against a policy: `{valid, issueCount, issues[{row,code,message}]}` |

Request body (`generate`): `policyId` **or** an inline `spec`, `operation` (`ADD` default | `UPDATE` | `REMOVE`), `seed` (repeatable data shape).
* **ADD** `options`: `families`, `spousePct`, `childPct`, `twinPct`, `parentPct`, `midTermPct`, `fillOptional` (state/city), `includePremiums`, `effectiveDate`,
  `sumInsured` (only used when the policy accepts any value), `flexPlan`, `employeePremium`, `employerPremium`; plus `codePrefix` (1-10 `A-Z0-9`, default `GEN`).
* **UPDATE / REMOVE**: `source` = rows of an earlier sheet (the `rows` of an ADD response), `count`, and for REMOVE `removeMode` (`FAMILY` | `MEMBER` | `MIX`).
  Stateless: the service remembers nothing about earlier sheets, so the members named always exist in the rows you handed back.

Errors: 400 bad request, 404 policy not found / not yours, **422 when the spec has gaps** (body lists them; nothing is generated and no codes are spent), 502 policy-service unreachable.

## What a generated sheet satisfies
One implementation of each rule (`gen/Rules.java`, `gen/SheetValidator.java`) is used both to build and to check a sheet; tests generate thousands of families per spec
and require zero validator issues.

| # | Rule | Source |
|---|---|---|
| R1 | Only relations the policy covers; exactly one SELF per family; family size ≤ min(policy max, construct max) | spec |
| R2 | Ages are whole years today; each relation within its window (`minAge`, `maxAge` from the relation rule, else the relation's own ceiling e.g. children 25), family `ageMin/ageMax`; adults ≥ 18 when no minimum is set | spec (`relationRules`, `family`) |
| R3 | Age-difference rule (`ageDifference` from SELF or SPOUSE) honoured for every member that has one | spec |
| R4 | Children ≤ `maxChildren`; codes `SON, SON_II…` / `DAUGHTER, DAUGHTER_II…` by birth order, only while the policy covers that code | spec |
| R5 | **Twins** (same DOB) only when every twin's relation rule has `maxTwins ≥ 2`, never more than the limit; no rule = no twins | spec |
| R6 | Parents **or** parents-in-law, never both; ≤ `maxParents`; in-laws only with a spouse; parent age ≤ relation max (default realism cap 80) | spec |
| R7 | Date of Marriage: in the past, ≥ 18 years after both birth dates, not after the effective date; only on the SELF and spouse rows | legacy rule |
| R8 | Date of Joining ≥ DOB + 18 years | legacy rule |
| R9 | Sum insured from the rate card (`ONE_OF`); any value when the card does not list limits (warns, default 500000) | spec |
| R10 | Grade / designation (from the rate card) and annual salary, on the **SELF row only**, when the premium type needs them | spec (`sheet`) |
| R11 | `NO_RULE` policies carry Employee and Employer Premium on every row | spec |
| R12 | Effective Date inside the policy period (inception date, or a mid-term date with `midTermPct`) — so pro-rata and refunds are exercised | spec |
| R13 | Dates are `dd-MM-yyyy` text; every cell is text (Excel never reformats a code or mobile); formulas are neutralised on export | — |
| R14 | Unique per broker: employee codes come from an atomic counter (`sheetgen.code_counters`, one `INSERT … ON CONFLICT DO UPDATE … RETURNING` per run), e-mails embed the code and broker id and end in `.test`, mobiles derive from the counter | server |

Generation is **refused (422)** when the spec has `gaps` (no active rate card, no family construct on a GMC policy…), when a needed grade/designation list is empty,
or when the age rules leave no valid employee age. Nothing is guessed.

### UPDATE and REMOVE
* REMOVE: whole families (employee leaving) and/or single dependants (never SELF alone). Effective Date = a leaving date between the member's join date and today (or policy end).
* UPDATE: one change per family from what the policy allows: first-name correction, mobile, e-mail, sum insured (family-wide, only values the rate card offers), grade, designation, salary.
  A date of birth is never "updated" blindly.

## The standalone HTML
`src/main/resources/static/index.html` is one file, no CDN, no build. Open it directly from disk or let this service serve it at `/`.
Load a spec (paste/open the JSON from policy-service, "Sample policy" for a demo, or — when served by this service — policy id + your token), pick ADD / UPDATE / REMOVE, generate,
preview, download `.xlsx` (a small built-in writer) or `.csv`, or press **Generate on server** to use the shared code counter.
Standalone codes are `prefix + number` that the tool remembers per browser (random prefix per browser), and e-mails carry a random run tag — but only the server counter *guarantees* uniqueness across testers.

**Drift guard.** The engine is the `<script id="engine">` block. `HtmlEngineTest` runs it under Node: its sheets must pass the **Java** validator, and the JS validator must flag exactly the
same (row, code) pairs as the Java one on 400 deliberately broken rows per spec. If either implementation changes a rule without the other, the test fails.
(The tests need `node` on the PATH; without it they are skipped, with a message.)

## Run / test
```
DATABASE_URL=jdbc:postgresql://localhost:5432/ensuretech DATABASE_USER=… DATABASE_PASSWORD=… \
POLICY_SERVICE_URL=http://localhost:8082 JWT_PUBLIC_KEY_LOCATION=file:../identity-service/keys/jwt-public.pem mvn spring-boot:run
mvn test      # 43 tests; needs PostgreSQL (schema created by Flyway) and, for HtmlEngineTest, Node
```
Tested end to end: sheets from both the service and the HTML tool were read by endorsement-service's intake (all headers auto-map at ≥ 0.9, zero row errors).

## NOT done / decisions for you
* **Premium types and SI subtypes beyond what the spec exposes.** The generator knows grade, designation, salary, flex plan, NO_RULE premiums and sum-insured lists. Rater-specific inputs the spec does not carry
  (state/city-wise limits, member-count tables, family-construct *shapes* like "1 adult + 2 kids" for construct raters, top-up base membership, OPD columns, LOL claims linkage) are **not generated**; they need the
  spec to say what each rater reads (extend `SheetSpecService`, then the generator). `fillOptional` adds state/city only as optional columns.
* Flex plan values are not in the spec, so `flexPlan` is a request option (blank + warning otherwise).
* Siblings, grandparents, step/adopted relations and domestic partners are never generated (validated if present).
* Realism caps that are **not** legacy rules: spouse within ±8 years, a child ≥ 18 years younger than the younger parent, parents ≤ 80.
* Ages are whole years as of the day of the run; effective dates in the future relative to a member's age are guarded (employee ≥ 18 on the effective date) but a very old policy period can still pair with old birth dates.
* UPDATE rows carry the Effective Date as the change date; legacy UPDATE also needs an insurer endorsement number (not generated).
* A spec fetched with a token is re-fetched on every call (no caching); fine for a testing tool.
