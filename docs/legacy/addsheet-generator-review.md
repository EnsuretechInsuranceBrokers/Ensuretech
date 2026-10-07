# Review: "Endorsement Add-Sheet Generator" (browser tool) against the legacy raters and our engine

**Verdict: it does not work for all raters.** It models 8 rater choices; the legacy code has ~24 active sum-insured subtypes and ~29 premium types
(`constant.php`). Even for the ones it models, running its generation engine (Node, 4,000 families) shows rows that the legacy validation would reject.
What I could not verify is flagged "unverified": I have read `premium_helper.php` / the update job's validation, but not `calculateSuminsured`.

## 1. Defects found by running its engine (4,000 families, family on, spouse 100 %, children 100 %)
| # | Defect | Evidence | Legacy rule broken |
|---|---|---|---|
| 1 | **Date of marriage ignores ages.** DOM is random in the last 10 years regardless of DOB | random DOB 21-58: employee under 18 at marriage in 253 families (6.3 %), spouse in 280 (7 %); with the tool's default fixed DOB the spouse still fails in 31 | DOB must not be after DOM and both must be 18+ at DOM (`dom_is_less_than_18_years`, `dob_is_grater_then_dom`) |
| 2 | **State rater fills no state** unless "fill non-mandatory fields" is on | `employee_state` absent for rater=state, optional off | state-wise raters read the state |
| 3 | **Construct raters are not generable.** `family_construct` writes no grade, designation or sum insured | all three absent | `GRADE/DESIGNATION/AGE_BAND_WITH_CONSTRUCT`, `GDAC*` need grade/designation/age **and** the family shape |
| 4 | **Children are unconstrained**: up to 4, no `max_child`, no twins (same DOB) | max 4 children; twin pairs only by accident (1-2 in 4,000) | policy `max_child` / `max_twins` (`isTwinChild`): more than `max_child` is allowed only for twins |
| 5 | **Parent ages up to 95** (839 of 4,000 families have a parent over 80) | | per-relation `max_age` in `policy_age_details` |
| 6 | **NO_RULE is not the same as flat.** The tool treats it as "flat" and leaves premiums off by default | | legacy NO_RULE premium type takes `employee_premium` + `employer_premium` straight from the row (premium is 0 without them) |
| 7 | Dependants carry no grade/designation | grade rater: dependants have none | unverified: the legacy GRADE rater reads `employee_grade` per row |
| 8 | Relationship text `Father-in-law` / `Mother-in-law` | | unverified: TPA label maps in legacy use "Father In Law"; `RelationMaster` names were not in the files |
| 9 | **Uniqueness memory is per browser** (`localStorage`) | two testers or a cleared cache reuse codes and emails | employee code unique per employer; email globally unique across all users (`email_already_taken`) |
| 10 | Only **ADD** sheets, and **Effective Date is always blank** | | pro-rata, refunds and mid-term pricing are never exercised; UPDATE needs an endorsement number |

Minor: header aliases miss `Insurer Removal Id`; `Is Dummy Email` etc. are fine.

## 2. Rater coverage
Legend: **Y** generates what the legacy code reads (verified), **P** partly, **N** cannot, **?** I have not read how the legacy code uses it.
| Legacy sum-insured subtype (id) | Tool | Sheet inputs needed |
|---|---|---|
| FLAT (1), NO_RULE (16) | Y / P (#6) | `Suminsured` must equal a configured flat value; NO_RULE also premiums |
| AGE (2) | P (#5) | DOB inside the per-relation age bands |
| GRADE (3), DESIGNATION (4) | P (#7) | grade / designation that has a rate row |
| NUMBER_OF_TIME_SALARY (5), RELATION_WISE_NUMBER_OF_TIME_SALARY (15), GRADE_WISE_NUMBER_OF_TIME_SALARY (18), PERCENTAGE_OF_SALARY (31) | P | annual salary + multiple (15: per member row); 18 also needs grade; 31 unknown |
| STATE_WISE (7) | N (#2) | state (and city) |
| MEMBER (6), MEMBER_WISE_DIFF_AND_MULTIPLE (17), MEMBER_COUNT_WISE (20) | N | per-relation / member-count rows: needs a family shape the tool does not control |
| FAMILY_CONSTRUCT_WISE (9), NEW_GRADE_WITH_CONSTRUCT (13), DESIGNATION_WITH_CONSTRUCT (14), GDAC (22) | N (#3) | grade/designation/age **and** an exact family construct |
| LOL_* (23-27) | N | `?` LOL flows are claims-linked; not read |
| BASE_WISE_SI_AND_PREMIUM (8), BASED_ON_BASE_POLICY (29), BASED_ON_BASE_SI_AND_TOPUP_PREMIUM (30), NUMBER_OF_TIME_SUMINSURED (28) | N | top-up policies: members must already exist on the base policy |
| Flex / base+enhance+flex, `Flex Plan`, `Self SI Selection` columns | N | `?` |
| **Premium types** (separate dimension: age, member age, per-mille by age/grade/designation, member-wise, state-wise, GDAC variants, family-percentage, GRAL, age-plus-relation, LOL, OPD twins) | N | the tool has no premium-type input at all; OPD is a single flat toggle |

## 3. How this tool and OUR engine fit (found and fixed in this change)
The tool's default output is **.xlsx in the legacy two-block layout** (`Employee *` columns on the Self row, `Insured Member *` columns on dependants). The engine could read neither:
- only CSV was accepted;
- the header matcher mapped `Employee DOB`, `Employee Email`, `Employee Mobile Number` to **employee_code** (0.68-0.83 confidence), `Employee Gender` to **grade**, `Employee State` to **full_name**, and left every `Insured Member *` column unmapped.
Fixed: `.xlsx` is read (streamed, zip-bomb guard, extension must match content, corrupt files are a 400); the two blocks map to their own fields and each row takes its side
(a dependant never borrows the employee's DOB or name); a "whose value is it" prefix is no longer evidence of a match; `Date of Joining` is a *fallback* effective date carried
from the Self row to the family, and an `Effective Date` column always wins. Tests: `tests/test_legacy_sheet.py`, `tests/test_schema.py` (209 engine tests pass).

## 4. What I recommend for the tool
1. **Drive it from the policy, not from free text.** Add `GET /api/v1/broker/policies/{id}/sheet-spec` to policy-service returning the rate card's grades, designations, valid sum insureds,
   age bands, relations, per-relation age windows, `max_child`, `max_twins`, premium type and SI subtype; the tool loads that JSON and can then never drift from the policy.
2. Fix #1 with `minDom = max(DOB+18y, spouseDOB+18y)`, `maxDom = yesterday`; skip the date (and say so) when the interval is empty. Fix #2 by making State/City part of the
   state rater, not of "optional". Cap children at `max_child` and generate twins on purpose (same DOB) up to `max_twins`; cap parent age at the relation's `max_age`.
3. Make NO_RULE require both premium columns; add grade + designation to construct/GDAC raters and let the user pick the family construct (e.g. "1 adult + 2 kids") explicitly.
4. Generate REMOVE and UPDATE sheets too, and an Effective Date inside the policy period, so pro-rata and refunds get exercised.
5. Per-tester code prefix (or a server-side counter) so codes and emails cannot collide.
