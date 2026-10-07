# Legacy rater definitions: every sum-insured subtype and premium type

Purpose: let a test-data generator build (a) a rate card per rater type and (b) ADD-sheet rows that the legacy engine would price. Facts only; anything not in the files is marked **UNKNOWN**.

Source abbreviations (all in `/root/.claude/uploads/67de6f74-a1ad-5054-89de-823ac734a875/`):
`c` = `e57b9876-constant.php`, `rh` = `d91a8bde-rater_helper.php`, `ph` = `53c4666b-premium_helper.php`, `sh` = `de1ec021-suminsured_helper.php`,
`pcc` = `73cc2d98-PolicyConfigurationController.php`, `eec` = `9cf75a42-EndrosementEnrollementController.php`, `tc` = `a2e3cff9-TestController.php`,
`job` = `b5d60a23-NewUpdateEndorsedMemberJob.php`. Cites are `file:line`.

## 0. Which code is the live engine

| Fact | Cite |
|---|---|
| Endorsement and update-job code call `RaterCalculator::calculateSuminsuredAndPremium($policy,$rowData,$masterEmployee)`. The class source is **not provided (UNKNOWN)**; the functions it drives are in `rh`: `getMembersCount` -> `calculateSuminsured` -> `getContribution` -> `calculatePremium` -> `updateExistingMembers`/`setSiAndPremium`. | eec:2903, job:1021, rh:382, 661, 2989, 3961, 6005, 7869 |
| `ph` `calculatePolicyMemberPremiumAmounts` and `sh` `calculatePolicyMemberCoverAmounts` are the older engine. Only `TestController` calls them. They use `MasterPremium::` constants (incl. removed "exclusion on number of parents" types) and a pre-ratio `calculatePremiumWithContribution`. **Use `rh` as the reference.** | tc:1270-1293, ph:383, ph:6614, sh:18 |
| `PolicyDTO` (resolves the IPD/OPD types for a policy) and `PermiliPremiumService` are not provided. Their behaviour is taken from `getPolicyData` (`rh:8827-8898`) and the deprecated `permiliPremiumCalculation` (`ph:25-36`). | rh:8827, ph:22-36 |
| Labels: `c` has constant names only. Human labels live in DB masters (`SuminsuredSubtypeMaster`, `MasterPremium`, admin CRUD routes). **Labels UNKNOWN**; the "Name" column below is derived from the constant. | c:34-76, 83-136; 4dd4a76e-admin_api.php:546-571 |

## 1. Shared model

### 1.1 Policy-level switches that select the rater
| Policy column | Meaning | Cite |
|---|---|---|
| `policy_rater_type_id` | IPD=1, OPD=2, IPD_PLUS_OPD=3, BASE_PLUS_TOP_UP=4, BASE_ENHANCE_FLEX=5. IPD side runs for 1,3,4,5; OPD side for 2,3. | c:176-183, rh:8830-8837 |
| `suminsurued_type_id` | `SuminsuredType` INDIVIDUAL=1, FAMILY_COVER=2, INDIVIDUAL_AND_FAMILY_COVER=3 (per-employee `employee_cover_type` then decides 1 or 2, default 2) | c:27-31, rh:822-826 |
| `suminsured_subtype_type_id`, `premium_type_id` (OPD: `opd_suminsured_sub_type`, `opd_premium_type`) | the subtype / premium type ids below | rh:8840-8852 |
| `premium_contribution_type` | NULL or 1 = by percentage; otherwise by value (OPD: `premium_contribution_type_opd`). For construct premium types a percentage policy is switched to BY_VALUE if any rate row has a `*_employee_contribution` > 118. | rh:8854-8862, rh:3057-3103, c:236-239 |
| `calculate_eligibility_from` (`salary_1`/`salary_2`/`salary_3`), `top_up_cover_has_eligibility` | which salary column feeds salary-multiple raters | rh:1204-1235, 1500-1530 |
| `is_prorata_premium_allowed`, `is_parent_policy`, `is_dependent_policy`, `different_si_for_different_set`, `no_of_member`, `max_child`, `topup_master_policy_id`, `policy_sub_type_id` | read by the raters (top-up-only subtypes need `policy_sub_type_id > 3`) | rh:10009, 1008, 1384, 1398, 2565, 4688 |
| `policy_age_details` (one row per relation) | `employee_contribution`, `employer_contribution`, `add_premium`, `is_opd_contribution`, `prorata_type_id` | rh:3573-3585, 10020 |

### 1.2 The rate table (`master_policy_rates`, model `MasterPolicyRates`)
Every rater type stores rows in one table keyed by `policy_id`. The DDL is **not provided (UNKNOWN)**; columns below are those read/written in `rh`/`pcc`/`tc`.

| Column group | Columns |
|---|---|
| Row class flags | `is_opd_rate`, `is_enchance_flex_rate`, `is_exceptional_rate`/`exceptional_rate_id`, `is_premium_rate`, `is_claim_rate`, `is_copayment_rate`+`copayment_percentage`, `is_month_wise_age`, `is_family_premium`, `is_member_premium`, `can_add_parent`, `can_add_parent_in_laws` (tc:228-250) |
| A row is an **SI row** when `total_premium IS NULL`, a **premium row** when `total_premium IS NOT NULL` (flat rows carry both) | rh:1088-1100, 4187-4200 |
| Flat | `flat_suminsured`, `flat_premium`, `tax`, `total_premium` (pcc:2521-2575) |
| Age | `age` string `"min-max"` (inclusive, first match wins; months when `is_month_wise_age=1`), `age_suminsured`, `age_premium`, `age_permilly`, `employee_age` (GDAC), `percentage` |
| Grade / designation / state | `grade_id`, `grade_suminsured`, `grade_premium`, `grade_permilly`; `designation_id`, `des_suminsured`, `des_premium`, `des_permilly`; `state_id`, `state_suminsured`, `state_premium` |
| Relation | `relation_id`, `relation_sum_insured`, `relation_premium`, `relation_permilly`, `count` (member sequence) |
| Salary multiple | `no_of_times_of_salary`, `min_si_limit`, `max_si_limit`, `rounding_type` (0 floor, 1 ceil, 2 round), `rounding_unit`, `si_calculation_percentage`, `no_of_time_suminsured` (pcc:2700-2750) |
| Construct | `no_of_adult`, `no_of_children`, `total_cover`, `cover_type`, `family_group_id` (-> `MasterFamilyGroup`: `no_of_employee/spouse/kids/parents/parents_in_law/partners/siblings`, `family_relation_ids`, `family_group`), `family_relation_id` (comma list), `is_family_relation_max_capping` |
| Per-relation SI | `self_suminsured`, `spouse_suminsured`, `partner_suminsured`, `kids_suminsured`, `single_parent_suminsured`, `double_parent_suminsured`, `single_parent_in_law_suminsured`, `double_parent_in_law_suminsured`, `others_suminsured` (rh:8444-8518) |
| Per-relation premium + contribution | `self_premium`, `spouse_premium`, `partner_premium`, `kids_premium`, `single_parent_premium`, `double_parent_premium`, `single_parent_in_law_premium`, `double_parent_in_law_premium`, `all_parent_premium`, `cross_parent_premium`, `others_premium`, and `<same>_employee_contribution` (kids: `per_kid_employee_contribution`) (rh:8520-8690) |
| Top-up | `base_suminsured`, `top_up_suminsured` (rh:4682-4700) |
| Other | `location`, `employee_gender` (GRAL, permily) |

### 1.3 What an ADD-sheet row feeds the rater
The sheet is stored as an `EndrosementDataDump` row; the full column list (field names) is visible in `eec:1962-2031` (`individualMemberData`). Header **labels** (`default_feild_name`) are seeded in DB (`DefaultEndrosementFormatFeildsMapping`, eec:1484-1492) and are **UNKNOWN**.

| rowData key given to the rater | Sheet / source field | Which row | Cite |
|---|---|---|---|
| `relation_id` | `relationship_with_employee` | member row | eec:2857, rh:663 |
| `employee_grade_id`, `employee_designation_id`, `employee_state_id` | `employee_grade`, `employee_designation`, `employee_state` -> stored on the employee master (`grade_id`, `designation_id`, `state_id`) | **employee (SELF) values, used for every member** | eec:2864-2866, job:979-981 |
| `employee_dob`, `insured_member_dob` | `employee_dob`, `insured_member_dob` | age of SELF = employee DOB; other members = their own DOB (fallback employee DOB) | rh:1042-1056 |
| `start_date` (cover start) | `effective_date` / `employee_date_of_joining` / midterm date | per member | eec:2891, rh:1054 |
| `ipd_suminsured`, `opd_suminsured` | `suminsured`, `opd_suminsured` | member row | eec:2862-2863 |
| `employee_annual_salary` (+`_2`,`_3`), `employee_variables_in_salary` (+`_2`,`_3`) | `employee_annual_salary`, `employee_variables_in_salary`, `..._2`, `..._3` (master `salary`, `pay_scale`, `salary_1`, `pay_scale_1`, `salary_2`, `pay_scale_2`) | **employee only** | eec:2884-2889 |
| `employee_number_of_time_salary` / `insured_member_number_of_time_salary` | `employee_number_of_time_salary` (SELF row) / `insured_member_number_of_time_salary` (dependants) | SELF / member | eec:2879-2880, job:1010-1011 |
| `employee_no_of_time_suminsured` | request `employee_no_of_time_suminsured` (SI subtype 28). Dump column: **UNKNOWN** | | eec:2850-2851 |
| `employee_percentage_of_salary` (subtype 31) | no dump column found. **UNKNOWN** | | rh:693 |
| `employee_cover_type` (SI type 3) | request `cover_type`; dump column **UNKNOWN** | | eec:2859 |
| `copayment_percentage`, `member_existing_claim` | request / member record; dump column **UNKNOWN** | | eec:2877, job (member_existing_claim) |
| `employee_gender`, `insured_member_gender` (GRAL, permily) | `employee_gender`, `insured_member_gender` | SELF / member | eec:2881 |
| `work_location` (GRAL) | listed in `getNewTataFields()`; dump column **UNKNOWN** | | 59e21a02-helper.php:9080 |
| `ipd_employee_premium`, `ipd_employer_premium`, `opd_*` (NO_RULE) | `employee_premium`, `employer_premium`, `opd_employee_premium`, `opd_employer_premium` | member row | rh:4945-4950 |
| `flex_plan`, "Self SI selection" | `flex_plan` exists in the dump (eec:2012); how it feeds raters = flex flow, **UNKNOWN** | | eec:3405, rh:9829 |
| `date_of_marriage`, `type_of_correction`, `endrosement_number` | dump columns, not read by raters (validation / UPDATE) | | job |

### 1.4 Member counts (construct inputs)
`getMembersCount` (rh:382-657): adults = existing members whose relation is not DAUGHTER/SON (so self, spouse, partner, parents, in-laws, siblings all count) (rh:575); children = DAUGHTER+SON with `is_twin_child=0` (rh:565). On ADD the new member is added: children +1 unless twin; for SI the adult is **not** incremented (only the Howden hard-code, rh:736-748) whereas `getContribution` (rh:3150-3160) and `calculatePremium` (rh:4055-4065) do `adultsCount++`. Whether SI is meant to see the pre-add count: **UNKNOWN**. `family_relation_ids` = all existing member relation ids (+ new one for ADD types 1,4).

Fallback rule used by every construct lookup: first exact (`no_of_adult = A and no_of_children = C`), else first row with `>=` on both (rh:1660-1665 and equivalents).

## 2. Sum-insured subtypes (`SuminsuredSubType`, c:34-76)

SI rule columns: "Lookup" = how the row is found (IPD; OPD mirrors it with `is_opd_rate=1`). Common filters on IPD lookups: `is_opd_rate=0`, `is_enchance_flex_rate=0`, and for SI rows `total_premium IS NULL` where stated. Policy picks rows with `is_exceptional_rate=0` unless the employee has an exceptional rate (rh:777-790). Salary: `salary = annual_salary - variables` when both set (variables NULL -> no subtraction), column set 1/2/3 chosen by `calculate_eligibility_from` (default 1) (rh:1204-1235).

| ID | Constant (name derived) | Rate row definition | Lookup / per-row sheet inputs | SI rule (and rounding) | Cite |
|---|---|---|---|---|---|
| 1 | FLAT (Flat) | `flat_suminsured` | sheet `suminsured` must equal a row value; family-cover with empty SI takes SELF's SI | SI = sheet value | rh:1029-1041, 855-885 |
| 2 | AGE (Age band) | `age` "a-b", `age_suminsured` | age at cover start from DOB (SELF=employee DOB, others=member DOB); prefers rows with `age_suminsured == suminsured`, else any | SI = `age_suminsured` of first matching band; none if age<0 | rh:1042-1087 |
| 3 | GRADE | `grade_id`, `grade_suminsured`, `total_premium` NULL | employee grade; sheet `suminsured` (else SELF's) | SI = `grade_suminsured` (exact SI match, else first row of the grade). Individual cover + UPDATE on SELF copies the SI to all members | rh:1088-1142 |
| 4 | DESIGNATION | `designation_id`, `des_suminsured` | employee designation; sheet `suminsured` | SI = `des_suminsured`; same copy-to-members rule | rh:1143-1196 |
| 5 | NUMBER_OF_TIME_SALARY | `no_of_times_of_salary`, `min_si_limit`, `max_si_limit`, `rounding_type`, `rounding_unit` | `employee_number_of_time_salary` must equal a row's value; salary 1/2/3 from employee | `SI = round(salary x multiple)` (PHP integer round), then if `> max` -> max, elif `< min` -> min (only when set), then `roundTo(type,unit)` if `rounding_type` set. Skipped when salary NULL. OPD branch has no NULL guards on limits | rh:1197-1311 |
| 6 | MEMBER | `relation_id`, `relation_sum_insured` | member relation; sheet `suminsured` | SI = `relation_sum_insured` (exact SI match else first row of relation) | rh:1312-1331 |
| 7 | STATE_WISE | `state_id`, `state_suminsured` | employee state | SI = `state_suminsured` (exact SI else first of state); none if state NULL | rh:1360-1383 |
| 8 | BASE_WISE_SI_AND_PREMIUM | top-up only (`policy_sub_type_id>3`): `base_suminsured`, `top_up_suminsured`, `total_premium`; rate sheet via `PolicyCustomRateImport` | sheet `suminsured` (top-up SI), base SI from base policy | SI = sheet value, no rate lookup | rh:1384-1397, pcc:2750-2780 |
| 9 | FAMILY_CONSTRUCT_WISE | `no_of_adult`, `no_of_children`, `total_cover`, `cover_type`, `is_claim_rate`, per-relation `*_suminsured` columns, `total_premium` NULL | counts A/C (1.4); row `total_cover == selected total cover` (SELF's `total_cover`, else sheet SI); `member_existing_claim` picks `is_claim_rate` | member SI = relation column of the row (SELF `self_suminsured`, SPOUSE `spouse_suminsured`, PARTNER `partner_suminsured`, children `kids_suminsured`, parent `single_parent_suminsured` if 1 parent else `double_parent_suminsured` on the **first** parent only (second = 0), parent-in-law analogous, siblings `others_suminsured`). `total_cover` = row `total_cover` | rh:1645-1792, 2534-2583, 8430-8518 |
| 13 | NEW_GRADE_WITH_CONSTRUCT | as 9 + `grade_id`; alt: `family_relation_id` (comma list, exact set of relation ids) with `is_family_relation_max_capping` | + employee grade | as 9; with `family_relation_id` rows SELF SI = `total_cover` (no capping) or `self_suminsured` (capping 1; `total_cover` raised to the sum of member columns, first `no_of_member` members); else error "Relation Not Covered" | rh:1793-1957, 2548-2583, 2937-2941 |
| 14 | DESIGNATION_WITH_CONSTRUCT | as 9 + `designation_id` (no `is_claim_rate` filter) | + employee designation | as 9 | rh:2334-2455 |
| 15 | RELATION_WISE_NUMBER_OF_TIME_SALARY | `relation_id`, `no_of_times_of_salary`, `relation_permilly` NULL, min/max/rounding | multiple = SELF row `employee_number_of_time_salary`, else `insured_member_number_of_time_salary`; **salary is the employee's** (variables subtracted if not NULL; always column 1) | `SI = round(employee salary x multiple)`, clamp max then min, `roundTo` if `rounding_type` set | rh:1428-1490 |
| 16 | NO_RULE | none | sheet `suminsured` | SI = sheet value, rater = true | rh:1631-1644 |
| 17 | MEMBER_WISE_DIFF_AND_MULTIPLE | `relation_id`, `relation_sum_insured`, optional `double_parent_suminsured`, `double_parent_in_law_suminsured` | type 1 (individual): row for relation with `relation_sum_insured == sheet SI`; type 2 (family): same, but for the first parent/in-law with `double_parent_*` set, SI = that grouped value; the 2nd parent gets 0 and the group SI is carried on the first parent | SI = `relation_sum_insured` (or grouped column) | rh:2032-2186, 4144-4180 |
| 18 | GRADE_WISE_NUMBER_OF_TIME_SALARY | `grade_id`, `no_of_times_of_salary`, min/max/rounding | grade + `employee_number_of_time_salary`; salary column per `calculate_eligibility_from` only when `top_up_cover_has_eligibility=1`, else column 1 | `SI = round(salary x multiple)`, clamp, `roundTo` | rh:1491-1630 |
| 20 | MEMBER_COUNT_WISE | `relation_id`, `count`, `relation_sum_insured` | `count` = sequence of the member in its group (self 1; Nth spouse; Nth child over sons+daughters; Nth parent; Nth parent-in-law); row with `count NULL` is the fallback | SI = `relation_sum_insured` | rh:1332-1359, 9206-9283 |
| 22 | GDAC | any of: `grade_id`, `designation_id`, `age` (or `employee_age`), `no_of_adult`/`no_of_children`, `family_group_id`, `total_cover`, `is_premium_rate=0` | `getGdacRater` narrows the rows by each dimension that is set on any row (grade, designation, employee-age band, member-age band, family group, construct) | member SI from the row's per-relation columns as in 9 | rh:1958-2031, 9291-9610 |
| 23 | LOL_GRADE_OF_EMPLOYEE | `grade_id`, `grade_suminsured` | employee grade | SI = sheet SI if given else row `grade_suminsured` (total cover = same) | rh:1088-1119, 2678-2685 |
| 24 | LOL_FLAT | `flat_suminsured` | sheet SI | SI = sheet SI | rh:2674-2677 |
| 25 | LOL_AGE_OF_EMPLOYEE | `age`, `age_suminsured` | like 2, SI from `ipd_suminsured` | SI = `age_suminsured` | rh:2637-2673 |
| 26 | LOL_GRADE_WITH_CONSTRUCT | `grade_id`, `grade_suminsured` (the construct lookup is commented out) | employee grade, sheet SI | SI = `grade_suminsured` | rh:2725-2803 |
| 27 | LOL_NUMBER_OF_TIMES_SALARY | as 5 | as 5 (IPD only) | as 5 but clamps apply only when the limit is non-zero | rh:2686-2724 |
| 28 | NUMBER_OF_TIME_SUMINSURED | `flat_suminsured` + `no_of_time_suminsured` (several rows per flat SI; config key `multiple_no_of_time_suminsured`) | sheet `suminsured` and `employee_no_of_time_suminsured` | SI = `no_of_time_suminsured x suminsured` | rh:2456-2475, pcc:2671-2698 |
| 29 | BASED_ON_BASE_POLICY | `si_calculation_percentage`, min/max/rounding | base policy SI (`ipd_base_suminsured`) | `SI = base x pct / 100`; below min -> min; above max -> max; `roundTo` if set; flex call uses the sheet SI | rh:2476-2533 |
| 30 | BASED_ON_BASE_SI_AND_TOPUP_PREMIUM | top-up only; premium rows `base_suminsured`,`top_up_suminsured`, + grade/designation/age/construct/`family_group_id` | sheet SI else SELF's SI | SI = that value | rh:1398-1427 |
| 31 | PERCENTAGE_OF_SALARY | `percentage` (+optional `age` band), min/max | member age band if rows have `age`; `employee_percentage_of_salary` filters the row | `SI = round(salary x pct/100)`, `> max` -> max elif `< min` -> min; **no** `roundTo` | rh:2804-2935 |
| 10, 11, 12 | GRADE/DESIGNATION_WITH_EXCLUSION_ON_NUMBER_OF_PARENTS, GRADE_WITH_CONSTRUCT | **Removed**, no active code in `rh` | | | c:77-80 |

## 3. Premium types (`PremiumType`, c:83-136)

Premium is then run through contribution and pro-rata (section 4). "Age" below is `dob.diffInYears(cover start)`. IPD shown; OPD mirrors with `is_opd_rate=1`. `SI` = the SI value just computed (family cover: the family `total_cover`).

| ID | Constant (name derived) | Rate row | Per-row sheet inputs | Premium rule | Cite |
|---|---|---|---|---|---|
| 1 | FLAT | `flat_suminsured`, `flat_premium`, `tax`, `total_premium` | SI | premium = `total_premium` of row `flat_suminsured == SI` | rh:4187-4204 |
| 2 | EMPLOYEE_AGE | `age`, `age_suminsured`, `total_premium` | **employee DOB** (every member uses the employee's age) | band containing age and `age_suminsured == SI`; if no row has `age_suminsured`, band-only rows. premium = `total_premium` | rh:4205-4245 |
| 3 | DESIGNATION | `designation_id`, `des_suminsured`, `total_premium` | employee designation | row with `des_suminsured == SI`, else designation row without SI | rh:4368-4390 |
| 4 | GRADE | `grade_id`, `grade_suminsured`, `grade_premium`, `total_premium` | employee grade | `grade_suminsured == SI`, else grade row without SI | rh:4340-4367 |
| 5 | PERMILY_AGE_WISE | `age`, `age_permilly`, `tax`, SI key (`age_suminsured`, or `no_of_times_of_salary`/`percentage` for salary subtypes), optional `employee_gender` | SELF=employee DOB, others=member DOB; children aged <=0 treated as 1; needs age>0; gender if rows carry it | `premium = SI x permilly / 1000`, plus `tax%` of that when `tax` set (service not provided, formula from `ph:25-36`) | rh:4391-4471, ph:25-36 |
| 6 | PERMILY_GRADE_WISE | `grade_id`, `grade_permilly`, `grade_suminsured`/`no_of_times_of_salary`, `tax`, optional `employee_gender` | employee grade, gender | as 5 using `grade_permilly` | rh:4472-4519 |
| 7 | PERMILY_DESIGNATION_WISE | `designation_id`, `des_permilly`, `des_suminsured`/`no_of_times_of_salary`, `tax` | employee designation, gender | as 5 using `des_permilly` | rh:4520-4563 |
| 8 | MEMBER_AGE | `age`, `age_suminsured`, `total_premium`, `is_month_wise_age` | **member's own DOB**; infants matched first on age in months where `is_month_wise_age=1`, then years | band premium `total_premium` | rh:4246-4339 |
| 9 | MEMBER_WISE | `relation_id`, `relation_sum_insured`, `relation_premium`, `total_premium` | member relation | `relation_sum_insured == SI`, else relation row without SI | rh:4564-4593 |
| 10 | STATE_WISE | `state_id`, `state_suminsured`, `total_premium` | employee state | `state_suminsured == SI`, else state row without SI | rh:4657-4681 |
| 13 | FAMILY_CONSTRUCT_WISE | `no_of_adult`, `no_of_children`, `total_cover`, `total_premium` NOT NULL, per-relation premium+contribution columns, `is_claim_rate`, `is_copayment_rate`/`copayment_percentage` | counts incl. the new member; `total_cover == SI/total cover` | member premium = relation column of the row (`self_premium`, `spouse_premium`, `kids_premium`, parent single/double/all/cross, ...) via `getRelationWiseSiAndPremium`; second parent of a pair gets 0 unless configured | rh:4958-5022, 5788-5815, 8160-8690 |
| 15 | NEW_GRADE_WITH_CONSTRUCT | as 13 + `grade_id`; `family_relation_id` fallback | + employee grade | as 13 | rh:5023-5133 |
| 16 | DESIGNATION_WITH_CONSTRUCT | as 13 + `designation_id` | + employee designation | as 13 | rh:5134-5199 |
| 17 | RELATION_WISE_NUMBER_OF_TIME_SALARY_PERMILY_RATE | rate row: `relation_id`, `no_of_times_of_salary`, `relation_permilly`, `tax`; plus SI row (same keys, `relation_permilly` NULL, min/max/rounding) | multiple of the member (dependant col, else employee col); employee salary | SI recomputed if absent (salary x multiple, clamp, roundTo); `premium = SI x relation_permilly / 1000` (+tax) | rh:4868-4941 |
| 18 | NO_RULE | none | sheet `employee_premium` + `employer_premium` (OPD: `opd_*`) | premium = sum of the two; **no contribution calc and no pro-rata** (`$ipdPremium` stays unset so rh:5901 is skipped) | rh:4942-4957, 5901 |
| 19 | AGE_BAND_WITH_CONSTRUCT | `age`, `is_premium_rate=1`, `total_cover`, `cover_type`, optional `grade_id`, per-relation premium+contribution columns, `is_claim_rate`, `is_copayment_rate` | member DOB; **age used = max(member age, the other parent's / cross-parent's age)** unless SI type is individual (then own age); some portal hard-codes | row = band containing age and `total_cover == SI`; member premium from relation column | rh:5200-5427 |
| 20 | MEMBER_COUNT_WISE | `relation_id`, `count`, `relation_sum_insured`, `relation_premium`, `total_premium` | sequence (as SI 20) | exact `count` (with SI match or no SI), else `count NULL` row | rh:4594-4656 |
| 21 | EMPLOYEE_AGE_BAND_WITH_CONSTRUCT | as 19 | **employee DOB** | as 19 | rh:5216-5219 |
| 22 | GDAC | as SI 22 with `is_premium_rate=1`, `total_cover == SI`, + `kids_premium` etc. | grade, designation, ages, relation list (parents ordered by creation), counts | `getGdacRater` then relation column | rh:5428-5514, 9291-9610 |
| 27 | GDAC_WITH_MAX_AGE | as 22 | age used = oldest member (incl. current; DELETE uses 0) | same | rh:9465-9490 |
| 32 | GDAC_WITH_SEQUENCE | as 22; group matched on `MasterFamilyGroup.family_relation_ids` + `family_group` string (F, M, FIL, MIL by sequence) | ordered parent/in-law relation ids | same | rh:9647-9657 |
| 23 | LOL_PERCENTAGE_OF_SI | `total_cover`, `total_premium` | SI | premium = `total_premium` of row `total_cover == SI`; employee/employer contributions = `total_premium x employee_contribution/100` (resp. employer) from `policy_age_details` of the relation | rh:5842-5864 |
| 24 | LOL_GRADE_WITH_CONSTRUCT | as 15 (same code block) | | as 15 | rh:5023-5133 |
| 25 | LOL_FLAT | `flat_suminsured`, `total_premium` | SI | premium = row `total_premium` | rh:5776-5784 |
| 26 | LOL_AGE_OF_EMPLOYEE | `age`, `total_cover`, `total_premium`, `self_premium`, `self_employee_contribution` | employee DOB | premium = **`self_premium`** of band row with `total_cover == SI`; employee contribution = `self_employee_contribution` | rh:5865-5900 |
| 28 | FAMILY_PERCENTAGE_WISE | rows: `flat_suminsured`, `age`, `relation_id`, `total_premium`, `is_premium_rate=1`, `is_family_premium` / `is_member_premium`; plus table `FamilyPercentageRaterConfiguration` (`suminsured`, `premium`, `premium_limitation` <=100, `family_size`>=1) | member DOB | premium = `total_premium` of the band row (family row preferred over member row). How the percentage config is applied: **UNKNOWN** (not read in `rh`). OPD branch tests `MEMBER_AGE` (looks like a bug) | rh:5515-5584, pcc:2143-2147, 5654-5688 |
| 29 | GRADE_WITH_BASE_SI | `grade_id`, `base_suminsured`, `top_up_suminsured`, `total_premium` | employee grade, base SI | row with grade, `base_suminsured`, `top_up_suminsured == SI` | rh:5585-5610 |
| 30 | AGE_PLUS_RELATION_WISE | `age`, `relation_id`, per-relation `*_suminsured`, `total_premium`, `is_premium_rate=1` | member DOB | band row of the relation whose relation SI column equals SI; contribution via `getRelationWiseContribution` | rh:5611-5686, 3513-3540, 10182 |
| 31 | GRAL | as 30 + `location`, `employee_gender` | `work_location`, member gender | as 30 | rh:5687-5775, 3541-3572 |
| 11, 12, 14 | GRADE/DESIGNATION_WITH_EXCLUSION_ON_NUMBER_OF_PARENTS, GRADE_WITH_CONSTRUCT | **Removed** | | | c:132-135 |

Top-up policy premiums keyed off the SI subtype (not the premium id): SI 8 -> row `base_suminsured` + `top_up_suminsured`, else `top_up_suminsured` only (rh:4682-4701); SI 30 -> by grade / designation / age band / adult-child counts (base policy members counted, `total_premium>0`) / `family_group_id` (rh:4702-4867). Premium id lists: construct `[13,15,16,22,24,27,32]`, age-relation `[19,21]`, GDAC `[22,27,32]`, LOL `[23,24,25,26]`, permily `[5,6,7]` (c:120-128).

## 4. Calculation rules common to all types

| Topic | Rule | Cite |
|---|---|---|
| Per member vs family | Raters run **per member row**. Construct types look up one family row then take the member's relation column; family SI/premium sits on the first parent (second = 0) unless `premium_calculate_for_both_parents`. The employee-level inputs (grade, designation, state, salary, multiple) are always taken from the employee (SELF) values. | rh:8160-8426, eec:2864-2889 |
| Rounding | SI salary raters use PHP `round()` to integer, then optional `roundTo(value, type, unit)`: `ROUND`/`CEIL`/`FLOOR` of `value/unit` times `unit` (unit NULL or 0 -> 10), cast to int. Premiums are **not rounded** in the rater (`ph`/EDME only rounds). | rh:10159-10179, c:1568-1572 |
| Contribution source | Non-construct premium types: `policy_age_details` row for the relation (`employee_contribution`, `employer_contribution`, `add_premium` added to premium). Construct and age-relation types: the rate row's `<rel>_employee_contribution`; employer = `100 - employee` (percentage) or `premium - employee` (value); if employee contribution is 0/NULL employer = 100% / full premium; premium 0 -> both 0. | rh:3573-3585, 8700-8730 |
| Pro-rata | Only if `is_prorata_premium_allowed` **and** cover start > policy start. Type from `policy_age_details.prorata_type_id` for the relation, default DAILY. DAILY: `premium / (policy days incl.) x (covered days incl.)`. MONTHLY: `ceil(float months)` for total and covered. QUARTERLY/HALF_YEARLY: TODO (no change). `invert=1` returns the uncovered part. | rh:9993-10075, c:228-233 |
| Employee/employer premium | By percentage: employee = premium x employee%/100, employer = premium x (100-employee%)/100. By value: employee = employee value, employer = premium - employee value; when pro-rated both are scaled by the same factor. | rh:10076-10106 |
| Application order | rate premium + `add_premium` -> pro-rata -> split. Errors: "Member can't be added - si rater not found" / "premium rater not found" (opd variants) when the row is missing. | rh:5901-5937, 2937-2975, 5939-5998 |
| Age basis | rater age = age at **cover start** (`diffInYears(start)`); SI age bands use `get_date_diff('year', start, dob)`. | rh:1054, 4212 |
| Twin children | Children counted for constructs exclude `is_twin_child=1`; twins allowed above `max_child` up to `max_twins`. | rh:565, 55-200 |

## 5. Bulk upload sheets for defining raters (Excel, Maatwebsite)

The import classes are **not provided**, so exact header strings are **UNKNOWN** except where the controller or a test controller shows them. Routing:

| Sheet field (request) | When | Import class | Cite |
|---|---|---|---|
| `si_file` / `in_suminsured_file` (individual) / `opd_si_file` / `topup_si_file` | SI subtype not flat (flat needs no file: `required_unless:si_sub_type,1`) | `PolicyConfiguratorSumInsuredImport(policyId, ip, suminsuredType, suminsuredSubType, isOpd, simplifiedRows, coverType)` | pcc:2082-2084, 2119-2120, 2843, 2896, 5227-5470 |
| same, SI subtype 8 or 30 | top-up | `PolicyCustomRateImport` | pcc:2776, 2885 |
| `premium_file` / `in_premium_file` / `opd_premium_file`, premium in CONSTRUCT_RATERS, AGE_RELATION_RATERS or LOL_RATERS | premium ids 13,15,16,22,24,27,32,19,21,23,25,26 | `PolicyConfiguratorPremiumImport(policyId, ip, premiumType, tax, raterType, ipdContribType, opdContribType, floaterCoverType)` | pcc:3062-3067, 5515-5632 |
| same, all other premium ids | 2-10, 17, 18(?), 20, 28-31 | `NewPolicyRaterSheetImportViaCollection(policyId, ip, premiumType, tax, isOpd)` | pcc:3069, tc:190-200, 1599 |
| JSON, no file | flat SI/premium (`sum_insureds[]{value,is_opd_rate,is_enchance_flex_rate}`, `premiums[]{amount,tax,total_premium,...}`); salary (`salary[]{no_of_times_of_salary,min_si_limit,max_si_limit,rounding_type,rounding_unit}`); base policy (`si_calculation_percentage,min_si_limit,max_si_limit,rounding_*`); `multiple_no_of_time_suminsured[]{suminsured,no_of_time_suminsured[]}`; `family_percentage_rater_config[]{suminsured,premium,premium_limitation,family_size}` | direct `MasterPolicyRates::create` | pcc:2449-2750, 2060-2072, 2143-2147 |

Column evidence that exists:
| Rater | Known layout | Cite |
|---|---|---|
| MEMBER_WISE_DIFF_AND_MULTIPLE SI sheet | a row per relation; heading `relationship` (values lower-cased; allowed: `self, spouse, partner, father, mother, father-in-law, mother-in-law, parents, parents-in-law, daughter, son, siblings`; `parents` cannot coexist with `father`/`mother`, likewise in-laws) and a `suminsured` group of SI columns; for individual SI type every relation needs at least one SI | pcc:2787-2825 |
| Legacy premium sheet (older `TestController::premiumFileImportTest`, premium ids 2, 8, 4, 3, 5, 6, 7, 9, 10) | row 0 = SI values across columns, row 1 = column labels `Premium` (or `Permilli` for permily types), `Can Add Parents`, `Can Add Parents In Law` (Yes/No/Y/N, default yes); column 0 = age band / grade name / designation name / relation name / state name; grade, designation, state are `firstOrCreate`d; `tax>0` -> `total_premium = premium + premium x tax/100`; permily rows store `total_premium=0` | tc:192-545 |
| Rate sample files for each type | stored via `SampleUploadController@uploadSampleFile` (data, not code) | 4dd4a76e-admin_api.php:936 |

## 6. Notes for the generator
1. A rate card for family constructs needs rows per `(grade|designation)` x `(adults, children)` x `total_cover`, each carrying the per-relation SI and premium columns; ADD rows must then use an exact A/C family (else the `>=` fallback applies).
2. Grade/designation/state/salary go on the **SELF row**; dependants need only relation, DOB, gender, `insured_member_number_of_time_salary` (subtype 15 only).
3. Salary-multiple raters need `employee_annual_salary` (+ variables) and a `employee_number_of_time_salary` that exists as a row; values are `round(salary x multiple)` then clamped and rounded per row.
4. NO_RULE needs `employee_premium` and `employer_premium` on every row; no pro-rata is applied to it.
5. MEMBER_COUNT_WISE and GDAC_WITH_SEQUENCE depend on member order (creation order).
6. Age raters depend on cover-start date; keep DOB inside bands and `Effective Date` inside the policy to exercise pro-rata.

## 7. UNKNOWN (not found in the provided files)
- Human labels for each SI subtype and premium type (DB masters / seeders).
- DDL of `master_policy_rates`, `policy_age_details`, `master_family_groups`; column types and nullability.
- Source of `RaterCalculator`, `PolicyDTO`, `PermiliPremiumService`, `DayWisePremiumService`; the permily formula is taken from the deprecated helper.
- Source of `PolicyConfiguratorSumInsuredImport`, `PolicyConfiguratorPremiumImport`, `NewPolicyRaterSheetImportViaCollection`, `PolicyCustomRateImport`, so **exact upload headers per rater type** (only the MEMBER_WISE_DIFF sheet and the old test layout are visible).
- Header labels of the endorsement ADD sheet (`default_feild_name`); only the snake_case field names (`eec:1962-2031`) are visible.
- Dump columns for `employee_percentage_of_salary`, `employee_cover_type`, `copayment_percentage`, `member_existing_claim`, `work_location`, `employee_no_of_time_suminsured`, base SI (`ipd_base_suminsured`), "Self SI selection" and how `flex_plan` drives raters.
- How `FamilyPercentageRaterConfiguration` is applied to premium type 28.
- Whether the SI construct lookup deliberately uses the pre-add adult count (rh:736-748 vs rh:3158, 4063).
- Flex / enhance-flex rows (`is_enchance_flex_rate`) calculation beyond the fact they are separate rows (`flexPremiumLoading`, `getFlexRelationWiseCalculationId`).
- Exact OPD-side differences beyond those noted (mirrors with `is_opd_rate=1`).
