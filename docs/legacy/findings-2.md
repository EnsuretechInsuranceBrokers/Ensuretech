# Legacy code findings, pass 2: what the business actually does

Read in this pass: `constant.php` (class index; `SuminsuredSubType`, `PremiumType`, `Relation`, `ProRataType`, `EndorsementQueueStatus`, `EndorsementQueueFileStatus`,
`PolicyType/SubType`, `CDAccountType`, `InstallmentLevel`, `RoundingType`, `RedFlags`, `CDBalanceConstants`), `NewUpdateEndorsedMemberJob.php` lines 1057-1750 (the whole
validation function), `rater_helper.php` function index + `isTwinChild` (start), `calculatePremiumWithProRata`, `checkPolicyRater`, `roundTo`, `getRelationWiseContribution`,
and the skeletons of `OptimizedIsEventBasisJob.php` and `optimizedEnrollmentClosedEmployeesJob.php`. **Not read**: the 6,000-line `calculatePremium`, `calculateSuminsured`,
`getContribution`, `getGdacRater`, flex/top-up logic, `suminsured_helper.php`, `admin_api.php`, `employee_api.php`, the rest of `helper.php`. Findings from earlier passes are in `findings-1.md`.

## 1. The business model, as the code describes it
- **Parties:** brokers (with RM/specializer staff), **employers (with parent/child companies**, `employer_child_companies`, behind a `PARENT_CHILD_LOGIC` flag), insurers
  (24 named), TPAs. One codebase runs many white-labelled "portals" (per-broker deployments with their own behaviour, see `Portal::`).
- **Policies:** type GROUP or TOP_UP; subtypes group mediclaim, group personal accident, group term life, voluntary top-ups of each, and LOL base/top-up. Policy status: approved&live,
  not approved, approved-but-not-live, inactive. Rater type: IPD, OPD, IPD+OPD, base+top-up, base+enhance+flex.
- **Sum insured and premium are separate rating dimensions** (about 30 sum-insured subtypes and about 30 premium types, ids in `constant.php`), each with an OPD twin, all looked up in
  one rate table (`master_policy_rates`) whose columns depend on the type (`age` "a-b", `grade_id`, `designation_id`, `state_id`, `flat_suminsured`, `total_premium`, per-relation
  `self_/spouse_/kids_/single_parent_...` suminsured, premium and **employee contribution**).
- **Who pays:** premium is split employee / employer, by percentage or by value, per relation; pro-rata applies to both parts.
- **Family rules per policy** live in `policy_age_details`, one row **per relation**: `min_age`, `max_age`, `age_difference` + `difference_from_relation` (a child must be N years younger
  than the employee or the spouse), `prorata_type_id`, `max_twins`; the policy has `max_child`, parents / parents-in-law counts and capping flags.
- **Money:** CD (client deposit) accounts have a **type: POLICY, GROUP, COMPANY or MULTIPLE** (one balance can fund several policies or a whole company) and installments at policy
  or employer level. Red-flag thresholds are fixed numbers (CD low balance 100000, claim pending 15 days, endorsement pending 7 days, 3+ endorsements). Rounding is configurable:
  floor / ceil / round to a unit (default 10) (`roundTo`).
- **Enrollment windows** have statuses (closed, open, confirmed, confirmed-but-not-opted) and mid-term joiners are priced from date of joining or date of upload, or not allowed.

## 2. Endorsement lifecycle in the legacy system (compare with `apps/jobs/states.py`)
A queue (`EndorsementQueueStatus`) with types ADD / REMOVE / UPDATE / COMBINE and 26 statuses. The part my engine covers (request raised, validation, discrepancy raised /
resolved, ready for premium calculation, insufficient CD balance, ready for endorsement, endorsement in progress, done) maps onto my phases INIT..EXECUTION. **The part after "done" is the
bulk of what is missing**: SENT_TO_INSURER, AWAITING_TPA_CONFIRMATION, TPA_CONFIRMATION_RECEIVED, AWAITING_ENDORSEMENT_COPY, ECARDS_AWAITED_FROM_TPA, IC_REPORT_APPROVAL_PENDING /
UNDER_PROCESS / DISCREPANCY_RAISED, plus PARTIAL_PROCESSED, CLOSED and REUPLOAD_IN_PROGRESS. So an endorsement is not finished when the roster is updated: it still has to reach the
insurer, be confirmed by the TPA, and produce e-cards and an endorsement copy. That is what my INTEGRATION phase is for, and nothing drives it yet. Files inside a queue have their own status set.

## 3. Validation rules the legacy update/correction job enforces (use as parity tests)
Corrections are addressed by a **per-member endorsement number** (not employee code + relation) and need a "type of correction". Rules:
- Names letters, dot and space only (`^[A-Z. ]*$`); mobile exactly 10 digits; pincode exactly 6 digits; gender male/female/other (case-insensitive variants); employee code `[a-zA-Z0-9 -/]`; email format and **globally unique** across users (soft-deleted users count as "inactive");
  employee code unique per employer for a SELF row, must exist for a dependant.
- State and city must exist in masters. Relation must be one of the policy's configured relations.
- DOB not in the future. Age must lie in the relation's `[min_age, max_age]`; with no range, adults must be 18+. For age-banded sum insured / premium, a band must exist for the age ("Cover amount / Premium amount not found for this age").
- Age difference: dependant must be at least `age_difference` years younger than the employee (or the spouse, per `difference_from_relation`).
- Marriage: DOB must not be after date of marriage and each spouse must be 18+ at marriage; the spouse's DOM must equal the employee's; changing the employee's DOM updates the spouse's.
- Special child: DOB cannot be changed. Sum insured must exist in the rate rows for that grade / designation / flat value; OPD sum insured likewise.
- **Age timing differs by purpose:** validation uses age **today**; pricing uses age at **cover start** (`premium_helper`). My engine uses the effective date for both.
Everything above is an *error* in legacy (one error list per row); I have not found a "warning" concept in this function.

## 4. Pro-rata details learned (extends findings-1)
- Pro-rata applies only if the policy allows it **and** the member's start is after the policy start; otherwise the full premium.
- DAILY: per-day = premium / (policy start..end inclusive); covered = (start..end inclusive). MONTHLY: months are `ceil` of Carbon's *fractional* month difference (policy start->end for the total, member start->end for covered).
  The `invert` flag returns the not-covered portion instead. QUARTERLY and HALF_YEARLY are still `TODO` in legacy. Pro-rata type is read **per relation** from `policy_age_details`.
- Twin children: more children than `max_child` are allowed when they are twins (same DOB) up to `max_twins`; some customer-specific tweaks exist (e.g. extra child allowance when a member
  has fewer than two parents, for one named group).

## 5. Differences from what I built (ranked by impact)
1. **Id spaces differ.** Legacy `Relation` ids (1 self, 2 spouse, 3 daughter, 4 son, 5 father, 6 mother, 7/8 in-laws, 10 partner, 12 brother, 15 sister, 99 other) and `PremiumType` /
   `SuminsuredSubType` ids are **not** the ids in the new DB design seeds I used (relation 3 = husband there; 32 premium types numbered differently). Any migration or rule that
   references legacy ids needs an explicit mapping table.
2. Per-relation policy rules (`policy_age_details`) do not exist in policy-service: age windows, age difference, per-relation pro-rata, max twins.
3. Contribution split and per-relation contributions: absent.
4. Post-endorsement lifecycle (insurer, TPA confirmation, e-cards, IC report): absent.
5. CD accounts spanning policies / groups / companies: billing-service is strictly one account per policy.
6. Parent/child employers: organization-service has no notion of child companies.
7. Rounding policy (floor/ceil/round to unit) and per-portal behaviour switches: absent.
8. My monthly pro-rata (`FULL_MONTH`) is not the legacy rule.

## 6. Suggested next steps (nothing built yet; please confirm order)
A. Add `policy_age_details`-style per-relation rules to policy-service (min/max age, age difference, pro-rata type, max twins) and enforce them in the engine's validation, with the rules above as tests.
B. Replace my pro-rata kinds with DAILY / MONTHLY (legacy semantics) per relation; add the contribution split and a rounding setting.
C. Add the id-mapping tables (relation, premium type, sum-insured subtype) and port the premium types production actually uses.
D. Drive the post-endorsement states from integration-service (insurer send, TPA confirmation, e-card, endorsement copy), including the TPA pull/reconcile described in findings-1.
E. Generalise CD accounts (policy / group / company) in billing-service.

## 7. Status of section 5 (updated)
Done: 1 (id mapping, `policy_svc.legacy_id_map` + `GET /api/v1/broker/lookups/legacy-ids`; many legacy premium/sum-insured types are UNMAPPED or APPROXIMATE because the new design's seeds decompose them differently), 2 (per-relation rules), 3 (contribution split), 4 (post-endorsement states and the internal lifecycle endpoint; the
insurer/TPA/e-card *adapters* that call it are still to build), 8 (DAILY/MONTHLY). Open: 5 (CD accounts spanning policies/groups/companies), 6 (parent/child employers), 7 (rounding policy and per-portal switches).
Rounding is blocked on the question of which policies round premiums (legacy rounds only for one portal); CD and parent/child are blocked on how you want ownership modelled.

## 8. Ported in the "wrap up" pass
- **Enrollment windows** (policy-service V9, `EnrollmentWindowEvaluator`, `/api/v1/broker/policies/{id}/enrollment-window/...`): policy-wide window, employee-level custom windows (the later end date wins, so a custom window can extend but never shorten; optional hard close date), confirmation that freezes the dates, confirmed-but-not-opted, mid-term by date of joining / date of upload / none, joiners from before the policy start, inception members, windows running past the policy end (cut at the end, or allowed N days after expiry), and an audit trail with the legacy scopes. One test per rule in `EnrollmentWindowEvaluatorTest`; the numbered comments match the legacy log codes.
  **Not ported**: parent/base policy chains and top-up base policies (they need employee-policy data this service does not hold), the whitelabel `Portal::` switches, the mail/WhatsApp triggers on window change, bulk extension from a spreadsheet (the bulk endpoint takes a list instead), and rollback logs.
  **Not yet wired**: the engine does not yet refuse an upload for an employee whose window is closed. The check exists (`/evaluate`); calling it from validation is the next step and needs a decision on whether a closed window blocks or only warns.
- **Announcements** (organization-service V4): banners / carousels / notices, targeted at all employers or a list, with a start date and a time to live; employers see only what is live and meant for them. **Not ported**: image upload and the media library (an image URL is stored instead), the module / sub-module placement mapping, Excel bulk create.

## 9. TPA offline member-id upload (ported)
The legacy "Offline TPA Member" format (employee code, relationship, TPA member id and name, e-card URL, cover start date, endorsement id) is ported as a
synchronous check-then-save flow for both the TPA's own users and the broker uploading for the TPA, with every legacy validation (see `docs/tpa-offline.md`
for the code-by-code table). Added beyond legacy: a board of who is waiting with hours-waiting urgency, a pre-filled template, per-line results with line
numbers, partial apply, an audit trail with the file hash, and `ID_IN_USE`. **Not ported**: claim re-keying on an id change, the 48-hour emailed result,
the employee-facing member-id/e-card email and WhatsApp, per-TPA claim columns.
