# Legacy premium rater upload sheets (premium types 1-32)

Source: legacy Laravel app `/home/user/udogg/wrapper/eb-backend_copy`. Read-only research; nothing was executed against a database. Companion machine-readable file: `premium-rater-sheets.json` (same content, keyed by premium type id).

Cite legend: `PCPI` = `app/Imports/Policy/PolicyConfiguratorPremiumImport.php`, `NPRS` = `app/Imports/Policy/NewPolicyRaterSheetImportViaCollection.php`, `controller` = `app/Http/Controllers/Broker/v1/PolicyConfigurationController.php`, `constant.php` = `app/Helpers/constant.php`, `helper.php` = `app/Helpers/helper.php`, samples = `public/SampleFormat/*.xlsx`. Importer branch spans are exact; a cite that is a branch span means 'inside this branch' (sub-line precision was not verified). Anything not provable from the files is marked **UNKNOWN**.

## 1. Which importer reads which premium type

The controller dispatches on premium type (controller:3064-3071 IPD, 3091-3113 individual-cover, 3152-3157 OPD, 5482-5640 API/quote path, 939-962 and 3389-3411 exceptional rates):

- `PolicyConfiguratorPremiumImport` (PCPI; `ToModel + WithHeadingRow`, row 1 = headings, matched by slug) when type is in `CONSTRUCT_RATERS [13,15,16,22,24,27,32]`, `AGE_RELATION_RATERS [19,21]` or `LOL_RATERS [23,24,25,26]` (constant.php:130-143). Types: **13, 15, 16, 19, 21, 22, 23, 24, 25, 26, 27, 32**.
- `NewPolicyRaterSheetImportViaCollection` (NPRS; `ToCollection`, **no headings**, row 1 = sum-insured values, row 2 = header labels compared as exact text) for everything else: **2, 3, 4, 5, 6, 7, 8, 9, 10, 17, 18(no branch), 20, 28, 29, 30, 31**.
- **Type 1 (FLAT)** has no sheet at all (flat premiums come from form fields `in_premium.*`/`premiums.*`; `in_premium_file` is `required_unless:premium_type,1`, controller:2119). Removed ids 11, 12, 14 are constants only (constant.php:145-147).

Heading slugs: `config/excel.php:60` sets `formatter => slug` (maatwebsite/excel 3.1 `Str::slug($heading,'_')`; the vendor package is not in the checkout so the exact function is library behaviour, not provable here). Result: case, spaces and punctuation are normalised, e.g. `Per Parents In Law` -> `per_parents_in_law`, `Set of Parent in Laws` -> `set_of_parent_in_laws`. NPRS sheets are NOT slugged: `Premium`, `premium`, `Permilli`, `permilli`, `Employee Gender`, `Family Premium(%)`, `Member Premium(%)`, `Can Add Parents`, `Can Add Parents In Law` are exact string matches (== in PHP, so case-sensitive).

## 2. Cross-cutting behaviour

- **IPD vs OPD**: identical sheet layouts. OPD upload uses `opd_premium_file` + `opd_premium_type` + `premium_tax_opd` + `premium_contribution_type_opd`, passes `RaterType::OPD` so every row gets `is_opd_rate=1`; deletion before insert is scoped to `is_opd_rate=1` (controller:3131-3157).
- **Contribution by percentage vs by value** (`ContributionType` BY_PERCENTAGE=1, BY_VALUE=0, constant.php:236-240): for PCPI types the request value `premium_contribution_type` (IPD) / `premium_contribution_type_opd` (OPD) selects the mode (PCPI:92-94). For 13/15/16/19/21: by value -> `getPremium(cell, tax)`; by percentage -> `intval(round(premiumCell * cell/100, 2) + tax%)` (`calculatePercentage`, PCPI:1677-1715) applied to the RAW premium cell. For 24/26 (LOL) percentage mode means premium cell = % of Total Cover and contribution = % of that premium. For 22/27/32 (GDAC) contribution cells are stored raw, mode ignored. For 30/31 the mode is read from the saved policy (`PolicyDTO`), not the request: by value -> `addTax(ec, tax)`, by percentage -> raw (NPRS:682-1050).
- **Tax**: `tax = premium_tax` only when `premium_tax_type == 1`, else 0 (controller:3054-3058). NPRS matrix types: premium column keeps the raw cell, `total_premium = premium*(1+tax/100)`, `tax` NULL when 0. Permilli types: `total_premium = 0`.
- **Delete before insert**: all rates for the policy with `total_premium NOT NULL`, `is_opd_rate`=0/1 as appropriate, `is_enchance_flex_rate=0`, `is_exceptional_rate=0` (type 17: `relation_permilly NOT NULL` instead) (controller:3048-3051, 3131-3140). Individual-cover upload deletes only `cover_type=1`.
- **Individual + family cover** (`si_type=3`): `premium_file` rows get `cover_type=2`; `in_premium_file` rows get `cover_type=1` (controller:3064-3069, 3091-3111; constant.php:30-31). Only PCPI types carry `cover_type`.
- **Exceptional rates**: same importers with `exceptionalRateId` -> `is_exceptional_rate=1`, `exceptional_rate_id`; `contribution_type` defaults to BY_PERCENTAGE (controller:939-962, 3389-3411).
- **Enhance / flex** (`RaterType::BASE_ENHANCE_FLEX`=5, `is_enchance_flex_rate=1`): rows are produced only from flat form entries (controller:2503-2590); neither importer contains any enhance/flex handling. `master_policies.enhance_premium_type` exists (app/Models/MasterPolicy.php:172) but a sheet format for it is **UNKNOWN**.
- **Top-up** (`RaterType::BASE_PLUS_TOP_UP`=4): `PolicyCustomRateImport` reads top-up SI/premium sheets (see section 5). The `topup_premium_file` branch imports `$data['premium_file']`, not the top-up file (controller:5582-5592) - looks like a legacy bug.
- **Errors**: neither importer raises a validation error. Missing/odd cells give NULL/0 or PHP `ErrorException`. Controller maps `ErrorException` to `{status:false, message:'Please upload proper excel file', log:...}` and any other `Exception` to `{status:false, errors:<message>}` (controller:3082-3085, 3167-3170). File-level rules: `premium_file` = `sometimes|required|file|mimes:xlsx,xlx,xls,csv` (controller:2084); `in_premium_file` = `required_if:si_type,3|required_unless:premium_type,1|file|mimes:xlx,xlsx` (2119).
- **Matrix (NPRS) gate**: if the first row has <=1 cell nothing is imported silently; rows whose col A is empty are dropped before processing (NPRS:65-72). Two optional header cells in row 2, exactly `Can Add Parents` and `Can Add Parents In Law`, set `can_add_parent`/`can_add_parent_in_laws` (value `N`/`NO` any case -> 0, else 1; absent -> 1) (NPRS:74-75). Note the plural/`Parents In Law` wording differs from PCPI headings (`Can Add Parent`, `Can Add Parent In Laws`; value `Y`/`YES` -> 1, anything else non-null -> 0, absent -> 1; PCPI:110-126).
- **Helper semantics**: `getAgeBand` ('>80'->'80-120', '<18'->'0-18', '25'->'25-25', else verbatim; PCPI:1717-1748); `getConstructCount` ('2A + 1C', spaces removed, split on '+', one digit + A/C; helper.php:6784); `addGrade`/`addDesignation` firstOrCreate by text (helper.php:7189,7210); relation text must equal `relation_masters.name` (Self, Spouse, Daughter, Son, Father, Mother, Father-in-law, ... seeded in database/seeds/MasterRelationSeeder.php); 'Spouse' falls back to a master named 'Spouse/Partner' (that row is not in the seeder: whether it exists is **UNKNOWN**).
- **Legacy single-header samples** (`ipd_opd_*_39..47,54`: Age/Grade/Designation/state/relation + Premium + Tax + Premium with Tax + Is Opd Premium, or Permilli + Is Opd Premium) do not match any current branch; NPRS would read their row 1 as the SI row. No code under app/Imports references 'Is Opd Premium'. Treat as **UNKNOWN / not loadable by the current importers**.

## 3. Index

| id | name | sample | importer | layout | header rows |
|---|---|---|---|---|---|
| 1 | FLAT | - | - | no-sheet | 0 |
| 2 | EMPLOYEE_AGE | employee_age_wise_premium_8.xlsx | NPRS (ToCollection) via branch NPRS:77-126 | matrix | 2 |
| 3 | DESIGNATION | designation_wise_si_premium_rater_9.xlsx | NPRS:172-219 | matrix | 2 |
| 4 | GRADE | grade_wise_si_premium_rater_10.xlsx | NPRS:127-171 | matrix | 2 |
| 5 | PERMILY_AGE_WISE | permilli_age_wise_si_rater_11.xlsx | NPRS:220-263 | matrix | 2 |
| 6 | PERMILY_GRADE_WISE | permilli_grade_wise_si_premium_rater_12.xlsx | NPRS:264-308 | matrix | 2 |
| 7 | PERMILY_DESIGNATION_WISE | permilli_designation_wise_si_premium_rater_13.xlsx | NPRS:309-353 | matrix | 2 |
| 8 | MEMBER_AGE | - | NPRS (ToCollection) via branch NPRS:77-126 | matrix | 2 |
| 9 | MEMBER_WISE | member_wise_si_premium_rater_35.xlsx | NPRS:354-409 | matrix | 2 |
| 10 | STATE_WISE | state_wise_si_premium_rater_52.xlsx | NPRS:491-541 | matrix | 2 |
| 13 | FAMILY_CONSTRUCT_WISE | family_construct_premium_sample_60.xlsx | PCPI:129-294 | heading-row table | 1 |
| 15 | NEW_GRADE_WITH_CONSTRUCT | new_grade_with_family_construct_premium_69.xlsx | PCPI:295-482 | heading-row table | 1 |
| 16 | DESIGNATION_WITH_CONSTRUCT | designation_with_family_construct_premium_71.xlsx | PCPI:483-646 | heading-row table | 1 |
| 17 | RELATION_WISE_NUMBER_OF_TIME_SALARY_PERMILY_RATE | relation_wise_number_of_time_permily_rate_73.xlsx | NPRS:542-583 | matrix | 2 |
| 18 | NO_RULE | - | - | no-sheet | 0 |
| 19 | AGE_BAND_WITH_CONSTRUCT | age_band_with_family_construct.xlsx | PCPI:647-807 | heading-row table | 1 |
| 20 | MEMBER_COUNT_WISE | member_count_wise_premium_sample_77.xlsx | NPRS:410-490 | matrix | 2 |
| 21 | EMPLOYEE_AGE_BAND_WITH_CONSTRUCT | employee_age_band_with_construct_79.xlsx | PCPI:647-807 | heading-row table | 1 |
| 22 | GDAC | gdac_premium_81.xlsx | PCPI:808-1075 | heading-row table | 1 |
| 23 | LOL_PERCENTAGE_OF_SI | perSiprem.xlsx | PCPI:1076-1129 | heading-row table | 1 |
| 24 | LOL_GRADE_WITH_CONSTRUCT | grade_with_family_construct_premium_lol_1337.xlsx | PCPI:1130-1349 | heading-row table | 1 |
| 25 | LOL_FLAT | - | - | no-sheet / UNKNOWN | 0 |
| 26 | LOL_AGE_OF_EMPLOYEE | lol_age_premium.xlsx | PCPI:1350-1543 | heading-row table | 1 |
| 27 | GDAC_WITH_MAX_AGE | gdac_with_max_age_premium_89.xlsx | PCPI:808-1075 | heading-row table | 1 |
| 28 | FAMILY_PERCENTAGE_WISE | family_percentage_premium_sample.xlsx | NPRS:584-629 | matrix | 2 |
| 29 | GRADE_WITH_BASE_SI | grade_with_base_si_premium_rater_sample_94.xlsx | NPRS:630-681 | matrix | 2 |
| 30 | AGE_PLUS_RELATION_WISE | age_plus_relation_wise_premium_rater_sample.xlsx | NPRS:682-926 | matrix | 2 |
| 31 | GRAL | gral_premium_rater_sample.xlsx | NPRS:927-1050 | matrix | 2 |
| 32 | GDAC_WITH_SEQUENCE | gdac_with_sequence_premium_rater_sample.xlsx | PCPI:808-1075 | heading-row table | 1 |

## 4. Per-type sheet definitions

### 1 - FLAT

- Sample: `none`
- Importer: none; layout: no-sheet; header rows: 0

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| premiums[].amount / tax / total_premium | - | `flat_premium / tax / total_premium` | number | yes | JSON form fields in_premium.* / premiums.* | controller:2119-2127,2517-2535 |

Derived by importer:
- flat_premium=amount, tax, total_premium written one MasterPolicyRates row per premium entry; enhance/flex flag is_enchance_flex_rate=1 set when entry has is_enchance_flex_rate [controller:2518-2590]

Notes / validation behaviour:
- Premium type 1 has NO excel sheet; premium_file is not read for it (in_premium_file is required_unless premium_type,1 [controller:2119]); neither importer has a branch for 1 [PCPI:67-124 NPRS:65-76]

### 2 - EMPLOYEE_AGE

- Sample: `employee_age_wise_premium_8.xlsx`
- Importer: NPRS (ToCollection) via branch NPRS:77-126; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["Age", "Premium", "Premium", "Premium"]`
  - row3+: `["0-18", 1000, 3000, 5000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Age (col A) | `Age` | `age` | string a-b | yes | e.g. '0-18','19-25'; month suffix allowed '6m-12m' => stored '6-12' and is_month_wise_age=1 [NPRS:1091-1119] | NPRS:77-126 |
| Premium (per SI column) | `Premium`, `premium` | `age_premium` | number | yes | header must be exactly 'Premium' or 'premium' | NPRS:77-126 |
| Suminsured (row 1 above each Premium column) | - | `age_suminsured` | number | yes |  | NPRS:77-126 |
| Can Add Parents | - | `can_add_parent` | Y/N | no |  | NPRS:77-126 |
| Can Add Parents In Law | - | `can_add_parent_in_laws` | Y/N | no |  | NPRS:77-126 |

Derived by importer:
- is_month_wise_age 0/1 from 'm' in age text
- tax / total_premium as common matrix tax rule
- is_premium_rate=1,is_opd_rate,is_exceptional_rate,exceptional_rate_id,ip_address,created_by
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:77-126]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:77-126]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:77-126]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]
- Empty premium cell under a 'Premium' header still inserts a row with total_premium 0 (no isset check) [NPRS:77-126]

### 3 - DESIGNATION

- Sample: `designation_wise_si_premium_rater_9.xlsx`
- Importer: NPRS:172-219; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["Designation", "Premium", "Premium", "Premium"]`
  - row3+: `["VP", 1000, 3000, 5000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Designation (col A) | `Designation` | `designation_id` | string | yes | addDesignation(): DesignationMaster firstOrCreate by name, auto-created [helper.php:7210-7230] | NPRS:172-219 |
| Premium | `Premium`, `premium` | `des_premium (raw) + total_premium (taxed)` | number | yes |  | NPRS:172-219 |
| Suminsured (row 1) | - | `des_suminsured` | number | yes |  | NPRS:172-219 |

Derived by importer:
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:172-219]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:172-219]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:172-219]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 4 - GRADE

- Sample: `grade_wise_si_premium_rater_10.xlsx`
- Importer: NPRS:127-171; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["Grade", "Premium", "Premium", "Premium"]`
  - row3+: `["A", 1000, 3000, 5000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Grade (col A) | `Grade` | `grade_id` | string | yes | addGrade(): MasterGrades firstOrCreate by grade text [helper.php:7189-7208] | NPRS:127-171 |
| Premium | `Premium`, `premium` | `grade_premium (raw) + total_premium (taxed)` | number | yes |  | NPRS:127-171 |
| Suminsured (row 1) | - | `grade_suminsured` | number | yes |  | NPRS:127-171 |

Derived by importer:
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:127-171]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:127-171]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:127-171]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 5 - PERMILY_AGE_WISE

- Sample: `permilli_age_wise_si_rater_11.xlsx`
- Importer: NPRS:220-263; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", null, 300000, 500000, 700000]`
  - row2: `["Age", "Employee Gender", "Permilli", "Permilli", "Permilli"]`
  - row3+: `["0-25", "Male", 0.56, 0.66, 0.76]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Age (col A) | `Age` | `age` | string | yes | age text stored verbatim (month suffix NOT parsed for type 5) |  |
| Employee Gender (optional col, must precede Permilli cols) | `Employee Gender` | `employee_gender` | string | no | header must be exactly 'Employee Gender' (row 2); value stored verbatim; applies to Permilli columns to its RIGHT in the same row |  |
| Permilli | `Permilli`, `permilli` | `age_permilly` | number (per-mille) | yes | stored as given; total_premium stored 0; premium computed later from permilli*SI (engine UNKNOWN here) |  |
| Suminsured (row 1) | - | `age_suminsured` | number | yes |  |  |

Derived by importer:
- total_premium = 0 (always)
- tax = request tax stored as-is on the row (not applied to anything here)
- is_premium_rate=1

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:220-263]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:220-263]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 6 - PERMILY_GRADE_WISE

- Sample: `permilli_grade_wise_si_premium_rater_12.xlsx`
- Importer: NPRS:264-308; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["Grade", "Permilli", "Permilli", "Permilli"]`
  - row3+: `["A", 0.56, 0.66, 0.76]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Grade (col A) | `Grade` | `grade_id` | string | yes | addGrade() firstOrCreate |  |
| Employee Gender (optional col, must precede Permilli cols) | `Employee Gender` | `employee_gender` | string | no | header must be exactly 'Employee Gender' (row 2); value stored verbatim; applies to Permilli columns to its RIGHT in the same row |  |
| Permilli | `Permilli`, `permilli` | `grade_permilly` | number (per-mille) | yes | stored as given; total_premium stored 0; premium computed later from permilli*SI (engine UNKNOWN here) |  |
| Suminsured (row 1) | - | `grade_suminsured` | number | yes |  |  |

Derived by importer:
- total_premium = 0 (always)
- tax = request tax stored as-is on the row (not applied to anything here)
- is_premium_rate=1

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:264-308]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:264-308]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 7 - PERMILY_DESIGNATION_WISE

- Sample: `permilli_designation_wise_si_premium_rater_13.xlsx`
- Importer: NPRS:309-353; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["Designation", "Permilli", "Permilli", "Permilli"]`
  - row3+: `["VP", 0.56, 0.66, 0.76]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Designation (col A) | `Designation` | `designation_id` | string | yes | addDesignation() firstOrCreate |  |
| Employee Gender (optional col, must precede Permilli cols) | `Employee Gender` | `employee_gender` | string | no | header must be exactly 'Employee Gender' (row 2); value stored verbatim; applies to Permilli columns to its RIGHT in the same row |  |
| Permilli | `Permilli`, `permilli` | `des_permilly` | number (per-mille) | yes | stored as given; total_premium stored 0; premium computed later from permilli*SI (engine UNKNOWN here) |  |
| Suminsured (row 1) | - | `des_suminsured` | number | yes |  |  |

Derived by importer:
- total_premium = 0 (always)
- tax = request tax stored as-is on the row (not applied to anything here)
- is_premium_rate=1

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:309-353]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:309-353]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 8 - MEMBER_AGE

- Sample: `none`
- Importer: NPRS (ToCollection) via branch NPRS:77-126; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["Age", "Premium", "Premium", "Premium"]`
  - row3+: `["0-18", 1000, 3000, 5000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Age (col A) | `Age` | `age` | string a-b | yes | e.g. '0-18','19-25'; month suffix allowed '6m-12m' => stored '6-12' and is_month_wise_age=1 [NPRS:1091-1119] | NPRS:77-126 |
| Premium (per SI column) | `Premium`, `premium` | `age_premium` | number | yes | header must be exactly 'Premium' or 'premium' | NPRS:77-126 |
| Suminsured (row 1 above each Premium column) | - | `age_suminsured` | number | yes |  | NPRS:77-126 |
| Can Add Parents | - | `can_add_parent` | Y/N | no |  | NPRS:77-126 |
| Can Add Parents In Law | - | `can_add_parent_in_laws` | Y/N | no |  | NPRS:77-126 |

Derived by importer:
- is_month_wise_age 0/1 from 'm' in age text
- tax / total_premium as common matrix tax rule
- is_premium_rate=1,is_opd_rate,is_exceptional_rate,exceptional_rate_id,ip_address,created_by
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:77-126]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:77-126]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:77-126]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]
- Empty premium cell under a 'Premium' header still inserts a row with total_premium 0 (no isset check) [NPRS:77-126]
- UNKNOWN: no dedicated sample for type 8; same branch/layout as type 2 (member_age shares branch with EMPLOYEE_AGE) [NPRS:77-126]

### 9 - MEMBER_WISE

- Sample: `member_wise_si_premium_rater_35.xlsx`
- Importer: NPRS:354-409; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 100000, 200000, 300000, 400000]`
  - row2: `["Relation", "premium", "premium", "premium", "premium"]`
  - row3+: `["Self", 1000, 2000, 3000, 4000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Relation (col A) | `Self`, `Spouse`, `Son`, `Daughter`, `Father`, `Mother`, `Father-in-law`, `Mother-in-law` | `relation_id` | string | yes | RelationMaster::where('name', ucfirst(text)); fallback 'Spouse' => 'Spouse/Partner'; unmatched => relation_id NULL, no error | NPRS:354-409 |
| premium | `Premium`, `premium` | `relation_premium (raw) + total_premium (taxed)` | number | yes |  | NPRS:354-409 |
| Suminsured (row 1) | - | `relation_sum_insured` | number | yes |  | NPRS:354-409 |

Derived by importer:
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:354-409]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value
- created_at/updated_at now()

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:354-409]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:354-409]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 10 - STATE_WISE

- Sample: `state_wise_si_premium_rater_52.xlsx`
- Importer: NPRS:491-541; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 300000, 500000, 700000]`
  - row2: `["State", "Premium", "Premium", "Premium"]`
  - row3+: `["Telangana", 1000, 3000, 4000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| State (col A) | `State` | `state_id` | string | yes | MasterState::firstOrCreate(state_name,status=1): unknown states are auto-created | NPRS:491-541 |
| Premium | `Premium`, `premium` | `state_premium (raw) + total_premium (taxed)` | number | yes |  | NPRS:491-541 |
| Suminsured (row 1) | - | `state_suminsured` | number | yes |  | NPRS:491-541 |

Derived by importer:
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:491-541]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:491-541]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:491-541]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 13 - FAMILY_CONSTRUCT_WISE

- Sample: `family_construct_premium_sample_60.xlsx`
- Importer: PCPI:129-294; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Family Construct | `family_construct` | `no_of_adult,no_of_children` | string | yes | '1A', '2A + 2C', '1A+1C' ; spaces removed, split on '+', digit+A / digit+C (single digit); 'A'='1A'; => getConstructCount [helper.php:6784-6860] | PCPI:129-294 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:129-294 |
| Total Premium | `total_premium` | `total_premium` | number | no |  | PCPI:129-294 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:129-294 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:129-294 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:129-294 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:129-294 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:129-294 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:129-294 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:129-294 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:129-294 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:129-294 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:129-294 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:129-294 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:129-294 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:129-294 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:129-294 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:129-294 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:129-294 |
| Cross Parent | `cross_parent` | `cross_parent_premium` | number | no |  | PCPI:129-294 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:129-294 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no | header must slug to all_parent_premium | PCPI:129-294 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:129-294 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:129-294 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:129-294 |

Derived by importer:
- total_premium = total_premium = addTax(Total Premium, tax) when tax>0 else raw (when 'Total Premium' present; if absent => 0) [PCPI:129-294]
- IPD/OPD contribution rule: x_employee_contribution = getPremium(cell, tax) (cell + tax pct) [PCPI:1692-1715] ; x_employee_contribution = intval(round(premiumCellRaw * cell/100, 2) [+tax pct]) via calculatePercentage [PCPI:1677-1690]
- Each premium cell: x_premium = getPremium(cell,tax) = cell + cell*tax/100 (int if whole else 2dp) [PCPI:1692-1715]
- is_premium_rate=1; cover_type=1 for in_premium_file / 2 for family cover [PCPI:129-294]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:129-294; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:129-294]; formulas are pre-calculated [PCPI:129-294]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:129-294; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:129-294]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:129-294]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample header row: Family Construct, Total Cover, Total Premium, Self, Self Employee Contribution, Spouse, ..., Partner, Partner Employee Contribution (sample has NO Can Add Parent/Claim Rate columns => defaults 1/0)
- Data rows e.g. ['1A',400000,12050,12050]  [sample row2]

### 15 - NEW_GRADE_WITH_CONSTRUCT

- Sample: `new_grade_with_family_construct_premium_69.xlsx`
- Importer: PCPI:295-482; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Grade | `grade` | `grade_id` | string | yes | addGrade() firstOrCreate; $row['grade'] accessed unguarded (header required) [PCPI:295-482] | PCPI:295-482 |
| Family Construct | `family_construct` | `no_of_adult,no_of_children` | string | yes | '1A', '2A + 2C', '1A+1C' ; spaces removed, split on '+', digit+A / digit+C (single digit); 'A'='1A'; => getConstructCount [helper.php:6784-6860] | PCPI:295-482 |
| Is Family Relation Max Capping | `is_family_relation_max_capping` | `is_family_relation_max_capping` | 0/1 (raw) | no | stored verbatim (sample uses 0/1; LOL sample uses 'N') | PCPI:295-482 |
| Family Relation | `family_relation` | `family_relation_id (CSV of relation ids)` | CSV of E,S,C,P,PIL,PR,SB | no | E=Self(1) S=Spouse(2) C=Daughter,Son(3,4) P=Father,Mother(5,6) PIL=FIL,MIL(7,8) PR=Partner(10) SB=Brother,Sister(12,15); sorted ascending [PCPI:1750-1790]; NOTE guard strlen($row['family_relation'] >= 1) is a typo (always truthy) | PCPI:295-482 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:295-482 |
| Total Premium | `total_premium` | `total_premium` | number | no |  | PCPI:295-482 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:295-482 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:295-482 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:295-482 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:295-482 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:295-482 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:295-482 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:295-482 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:295-482 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:295-482 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:295-482 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:295-482 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:295-482 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:295-482 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:295-482 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:295-482 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:295-482 |
| Cross Parent | `cross_parent` | `cross_parent_premium` | number | no |  | PCPI:295-482 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:295-482 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no | header must slug to all_parent_premium | PCPI:295-482 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:295-482 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:295-482 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:295-482 |

Derived by importer:
- total_premium = total_premium = addTax(Total Premium, tax) when tax>0 else raw [PCPI:295-482]
- IPD/OPD contribution rule: x_employee_contribution = getPremium(cell, tax) (cell + tax pct) [PCPI:1692-1715] ; x_employee_contribution = intval(round(premiumCellRaw * cell/100, 2) [+tax pct]) via calculatePercentage [PCPI:1677-1690]
- Each premium cell: x_premium = getPremium(cell,tax) = cell + cell*tax/100 (int if whole else 2dp) [PCPI:1692-1715]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:295-482; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:295-482]; formulas are pre-calculated [PCPI:295-482]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:295-482; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:295-482]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:295-482]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sheet 'T&C' in sample is a help note; only sheet 1 data is meaningful (WithMultipleSheets::sheets() returns one importer instance used for all sheets [PCPI:295-482])
- Per T&C note: leave Family Construct blank and set Is Family Relation Max Capping 0 (single premium in Self) or 1 (relation-wise premiums); Family Relation lists allowed relations for the grade [sample sheet 'T&C' A1]
- UNKNOWN: whether engine reads is_family_relation_max_capping beyond storing it
- grade_plus_family_construct_premium_sample_65.xlsx (older 'Grade'+'Family Construct') uses header 'Per Parent In Law' which slugs to per_parent_in_law and is NOT read (importer reads per_parents_in_law) => that premium column would be dropped [sample row1; PCPI:295-482]

### 16 - DESIGNATION_WITH_CONSTRUCT

- Sample: `designation_with_family_construct_premium_71.xlsx`
- Importer: PCPI:483-646; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Designation | `designation` | `designation_id` | string | yes | addDesignation(); $row['designation'] accessed unguarded | PCPI:483-646 |
| Family Construct | `family_construct` | `no_of_adult,no_of_children` | string | yes | '1A', '2A + 2C', '1A+1C' ; spaces removed, split on '+', digit+A / digit+C (single digit); 'A'='1A'; => getConstructCount [helper.php:6784-6860] | PCPI:483-646 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:483-646 |
| Total Premium | `total_premium` | `total_premium` | number | no |  | PCPI:483-646 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:483-646 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:483-646 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:483-646 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:483-646 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:483-646 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:483-646 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:483-646 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:483-646 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:483-646 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:483-646 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:483-646 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:483-646 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:483-646 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:483-646 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:483-646 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:483-646 |
| Cross Parent | `cross_parent` | `cross_parent_premium` | number | no |  | PCPI:483-646 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:483-646 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no | header must slug to all_parent_premium | PCPI:483-646 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:483-646 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:483-646 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:483-646 |

Derived by importer:
- total_premium = RAW 'Total Premium' (tax NOT applied for type 16 unlike 13/15) or 0 [PCPI:483-646]
- IPD/OPD contribution rule: x_employee_contribution = getPremium(cell, tax) (cell + tax pct) [PCPI:1692-1715] ; x_employee_contribution = intval(round(premiumCellRaw * cell/100, 2) [+tax pct]) via calculatePercentage [PCPI:1677-1690]
- Each premium cell: x_premium = getPremium(cell,tax) = cell + cell*tax/100 (int if whole else 2dp) [PCPI:1692-1715]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:483-646; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:483-646]; formulas are pre-calculated [PCPI:483-646]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:483-646; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:483-646]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:483-646]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample header order: Designation, Family Construct, Can Add Parent, Can Add Parent In Laws, Total Cover, Total Premium, Self, ... Partner Employee Contribution

### 17 - RELATION_WISE_NUMBER_OF_TIME_SALARY_PERMILY_RATE

- Sample: `relation_wise_number_of_time_permily_rate_73.xlsx`
- Importer: NPRS:542-583; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["No of Time Salary", 1, 2, 3]`
  - row2: `["Relation", "Permilli", "Permilli", "Permilli"]`
  - row3+: `["Self", 0.2, 0.3, 0.4]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Relation (col A) | `Self`, `Spouse`, `Son` | `relation_id` | string | yes | RelationMaster::where('name', text) (exact, no ucfirst); 'Spouse' falls back to 'Spouse/Partner' | NPRS:542-583 |
| Permilli | `Permilli`, `permilli` | `relation_permilly` | number (per-mille) | yes | null cells skipped | NPRS:542-583 |
| No of Time Salary (row 1) | - | `no_of_times_of_salary` | number | yes | row 1 col A label 'No of Time Salary'; value above each Permilli col | NPRS:542-583 |

Derived by importer:
- No total_premium written (only relation_permilly); controller deletes by relation_permilly NOT NULL [controller:3048-3049]
- tax stored as-is

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:542-583]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:542-583]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 18 - NO_RULE

- Sample: `none`
- Importer: none; layout: no-sheet; header rows: 0

Notes / validation behaviour:
- NO_RULE=18 constant exists [constant.php:99]; neither importer has a branch for 18 and it is not in CONSTRUCT/AGE_RELATION/LOL lists => NewPolicyRaterSheetImportViaCollection would run and insert nothing (finalDataToInsert empty) [NPRS:65-76]; UNKNOWN intended use

### 19 - AGE_BAND_WITH_CONSTRUCT

- Sample: `age_band_with_family_construct.xlsx`
- Importer: PCPI:647-807; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Age Band | `age_band` | `age` | string | yes | getAgeBand(): '>80'=>'80-120', '<18'=>'0-18', single digits '25'=>'25-25', otherwise verbatim 'a-b' [PCPI:1717-1747] | PCPI:647-807 |
| Grade | `grade` | `grade_id` | string | no | optional; addGrade() if present | PCPI:647-807 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:647-807 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:647-807 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:647-807 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:647-807 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:647-807 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:647-807 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:647-807 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:647-807 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:647-807 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:647-807 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:647-807 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:647-807 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:647-807 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:647-807 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:647-807 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:647-807 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:647-807 |
| Cross Parent | `cross_parent` | `cross_parent_premium` | number | no |  | PCPI:647-807 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:647-807 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no | header must slug to all_parent_premium | PCPI:647-807 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:647-807 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:647-807 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:647-807 |

Derived by importer:
- total_premium ALWAYS 0: code references undefined $totalPremiumWithTax => 0 [PCPI:647-807]
- IPD/OPD contribution rule: x_employee_contribution = getPremium(cell, tax) (cell + tax pct) [PCPI:1692-1715] ; x_employee_contribution = intval(round(premiumCellRaw * cell/100, 2) [+tax pct]) via calculatePercentage [PCPI:1677-1690]
- Each premium cell: x_premium = getPremium(cell,tax) = cell + cell*tax/100 (int if whole else 2dp) [PCPI:1692-1715]
- no no_of_adult/no_of_children, no can_add_parent written for 19/21 [PCPI:647-807]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:647-807; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:647-807]; formulas are pre-calculated [PCPI:647-807]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:647-807; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:647-807]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:647-807]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample: Age Band, Total Cover, Self, Self Employee Contribution, Spouse, ... Partner, Partner Employee Contribution. Row '0-16' has no Self/Spouse (kids/parents only) so those stay NULL
- Relation-by-age rater: one row per age band, premium per relation in columns

### 20 - MEMBER_COUNT_WISE

- Sample: `member_count_wise_premium_sample_77.xlsx`
- Importer: NPRS:410-490; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", 100000, 200000, 300000, 400000, 500000]`
  - row2: `["Relation", "premium", "premium", "premium", "premium", "premium"]`
  - row3+: `["Self", 100, 0, 0, 0, 0]`
  - more: `["Child 1", 0, 0, 300, 0, 0]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Relation + count (col A) | `Self`, `Spouse`, `Partner`, `Child 1`, `Child 2`, `Son 1`, `Daughter 1`, `Parent`, `Father`, `Mother`, `Parent-in-law`, `Father-in-law`, `Mother-in-law` | `relation_id (+count)` | '<Relation> <n>' | yes | split on space; token1 ucfirst matched: Self=>[1] count forced 1; Spouse=>[2]; Partner=>[10]; Child=>[3,4] (two rows); Son=>[4]; Daughter=>[3]; Parent=>[5,6]; Father=>[5]; Mother=>[6]; Parent-in-law=>[7,8]; Father-in-law=>[7]; Mother-in-law=>[8]; token2 => count (NULL if absent). Unrecognised token reuses previous row's ids (stale var) or fatals on first row | NPRS:410-490 |
| premium | `Premium`, `premium` | `relation_premium (raw) + total_premium (taxed)` | number | yes | formulas like '=0' evaluated (WithCalculatedFormulas) [sample] | NPRS:410-490 |
| Suminsured (row 1) | - | `relation_sum_insured` | number | yes |  | NPRS:410-490 |

Derived by importer:
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:410-490]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value
- column count = member ordinal/number
- zero cells still inserted (no isset check)

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:410-490]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:410-490]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 21 - EMPLOYEE_AGE_BAND_WITH_CONSTRUCT

- Sample: `employee_age_band_with_construct_79.xlsx`
- Importer: PCPI:647-807; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Age Band | `age_band` | `age` | string | yes | getAgeBand(): '>80'=>'80-120', '<18'=>'0-18', single digits '25'=>'25-25', otherwise verbatim 'a-b' [PCPI:1717-1747] | PCPI:647-807 |
| Grade | `grade` | `grade_id` | string | no | optional; addGrade() if present | PCPI:647-807 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:647-807 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:647-807 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:647-807 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:647-807 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:647-807 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:647-807 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:647-807 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:647-807 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:647-807 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:647-807 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:647-807 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:647-807 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:647-807 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:647-807 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:647-807 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:647-807 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:647-807 |
| Cross Parent | `cross_parent` | `cross_parent_premium` | number | no |  | PCPI:647-807 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:647-807 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no | header must slug to all_parent_premium | PCPI:647-807 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:647-807 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:647-807 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:647-807 |

Derived by importer:
- total_premium ALWAYS 0: code references undefined $totalPremiumWithTax => 0 [PCPI:647-807]
- IPD/OPD contribution rule: x_employee_contribution = getPremium(cell, tax) (cell + tax pct) [PCPI:1692-1715] ; x_employee_contribution = intval(round(premiumCellRaw * cell/100, 2) [+tax pct]) via calculatePercentage [PCPI:1677-1690]
- Each premium cell: x_premium = getPremium(cell,tax) = cell + cell*tax/100 (int if whole else 2dp) [PCPI:1692-1715]
- no no_of_adult/no_of_children, no can_add_parent written for 19/21 [PCPI:647-807]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:647-807; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:647-807]; formulas are pre-calculated [PCPI:647-807]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:647-807; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:647-807]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:647-807]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample: Age Band, Total Cover, Self, Self Employee Contribution, Spouse, ... Partner, Partner Employee Contribution. Row '0-16' has no Self/Spouse (kids/parents only) so those stay NULL
- Relation-by-age rater: one row per age band, premium per relation in columns
- Same branch as 19 (age = employee age band semantic); sample headers identical to 19 sample [PCPI:647-807]

### 22 - GDAC

- Sample: `gdac_premium_81.xlsx`
- Importer: PCPI:808-1075; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Construct | `construct` | `no_of_adult,no_of_children` | string '2A+1C' | no | types 22,27 only (skipped for 32) | PCPI:808-1075 |
| Grade | `grade` | `grade_id` | string | no |  | PCPI:808-1075 |
| Designation | `designation` | `designation_id` | string | no |  | PCPI:808-1075 |
| Age Band | `age_band` | `age` | string | no | getAgeBand() ('>80','<18','25' conventions) | PCPI:808-1075 |
| Employee Age Band | `employee_age_band` | `employee_age` | string | no | stored verbatim, NOT passed through getAgeBand | PCPI:808-1075 |
| Base Suminsured | `base_suminsured` | `base_suminsured` | number | no | optional | PCPI:808-1075 |
| Family Group | `family_group` | `family_group_id (MasterFamilyGroup)` | string | no | 22/27: comma list of [n]CODE with CODE in E,S,C,P,PIL,PR,SB (single leading digit = count; e.g. 'E,S,2C','E,S,2P,2PIL,SB'); special 'P/PIL(2/3/4)' expands to multiple rates. 32: ordered list of F,M,FIL,MIL e.g. 'F, MIL' | PCPI:808-1075 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:808-1075 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Self Premium | `self_premium` | `self_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Spouse Premium | `spouse_premium` | `spouse_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Kid Premium | `per_kid_premium` | `kids_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Parent Premium | `per_parent_premium` | `single_parent_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Parent In Law Premium | `per_parent_in_law_premium` | `single_parent_in_law_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Set of Parents Premium | `set_of_parents_premium` | `double_parent_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Set of Parent In Laws Premium | `set_of_parent_in_laws_premium` | `double_parent_in_law_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Set of Parent In Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Others Premium | `others_premium` | `others_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Partner Premium | `partner_premium` | `partner_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Cross Parent Premium | `cross_parent_premium` | `cross_parent_premium` | number | no |  | PCPI:808-1075 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:808-1075 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no |  | PCPI:808-1075 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:808-1075 |

Derived by importer:
- tax column stored NULL, total_premium stored 0 (always); comment: 'Tax logic added in Rater Helper & Facade for this Rater' [PCPI:808-1075]
- premium/contribution cells copied RAW (no tax, no pct conversion) - contribution type ignored [PCPI:808-1075]
- family group: MasterFamilyGroup::firstOrCreate on member counts [22/27 keyed WITHOUT the string; 32 keyed WITH family_group string] [PCPI:808-1075]
- 'P/PIL(4)' => rates for P,PIL,[P,PIL],2P,2PIL,[P,2PIL],[2P,PIL],[2P,2PIL]; 'P/PIL(3)' => first 7; 'P/PIL(2)' => first 5; one MasterPolicyRates row per expansion [PCPI:1548-1670]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:808-1075; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:808-1075]; formulas are pre-calculated [PCPI:808-1075]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:808-1075; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:808-1075]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:808-1075]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Family group token parse only reads first char as count (so '10C' unsupported); empty token => PHP offset error [PCPI:808-1075]
- If both 'family_group' cell empty: no family_group_id stored
- PremiumType::GDAC_RATERS=[22,27,32] [constant.php:141]
- Sample header: Construct, Grade, Designation, Family Group, Employee Age Band, Total Cover, Self Premium, Self Employee Contribution, ... Partner Premium, Partner Employee Contribution, Cross Parent Premium, Cross Parent Employee Contribution, All Parent Premium, All Parent Employee Contribution
- Sample family groups: A:'E', B:'E,S,C', C:'E,S,2C', D:'E,S,P,PIL', E:'E,S,2P,2PIL', F:'E,S,2P,2PIL,SB'

### 23 - LOL_PERCENTAGE_OF_SI

- Sample: `perSiprem.xlsx`
- Importer: PCPI:1076-1129; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Grade | `grade` | `grade_id` | string | no | optional | PCPI:1076-1129 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:1076-1129 |
| Total Premium | `total_premium` | `total_premium (derived)` | number = percentage of cover | yes | cell is a PERCENT of Total Cover, not an amount | PCPI:1076-1129 |

Derived by importer:
- total_premium = calculatePercentage(total_cover, cell, tax) = intval(round(cover*cell/100,2)+tax pct) [PCPI:1076-1129]
- employee_contribution = total_premium * PolicyAgeDetails.employee_contribution/100; employer_contribution = total_premium * PolicyAgeDetails.employer_contribution/100 ; if no policy_age_details row the vars are undefined (PHP error) [PCPI:1076-1129]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:1076-1129; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:1076-1129]; formulas are pre-calculated [PCPI:1076-1129]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:1076-1129; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:1076-1129]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:1076-1129]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample: header 'Total Cover','Total Premium'; rows [500000,10],[600000,11],[700000,12]
- Contribution type selection (is_opd) is parsed but unused in this branch [PCPI:1076-1129]

### 24 - LOL_GRADE_WITH_CONSTRUCT

- Sample: `grade_with_family_construct_premium_lol_1337.xlsx`
- Importer: PCPI:1130-1349; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Grade | `grade` | `grade_id` | string | yes | addGrade() firstOrCreate; $row['grade'] accessed unguarded (header required) [PCPI:1130-1349] | PCPI:1130-1349 |
| Family Construct | `family_construct` | `no_of_adult,no_of_children` | string | yes | '1A', '2A + 2C', '1A+1C' ; spaces removed, split on '+', digit+A / digit+C (single digit); 'A'='1A'; => getConstructCount [helper.php:6784-6860] | PCPI:1130-1349 |
| Is Family Relation Max Capping | `is_family_relation_max_capping` | `is_family_relation_max_capping` | 0/1 (raw) | no | stored verbatim (sample uses 0/1; LOL sample uses 'N') | PCPI:1130-1349 |
| Family Relation | `family_relation` | `family_relation_id (CSV of relation ids)` | CSV of E,S,C,P,PIL,PR,SB | no | E=Self(1) S=Spouse(2) C=Daughter,Son(3,4) P=Father,Mother(5,6) PIL=FIL,MIL(7,8) PR=Partner(10) SB=Brother,Sister(12,15); sorted ascending [PCPI:1750-1790]; NOTE guard strlen($row['family_relation'] >= 1) is a typo (always truthy) | PCPI:1130-1349 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:1130-1349 |
| Total Premium | `total_premium` | `total_premium` | number | no |  | PCPI:1130-1349 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:1130-1349 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:1130-1349 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:1130-1349 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:1130-1349 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:1130-1349 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:1130-1349 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:1130-1349 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:1130-1349 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:1130-1349 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:1130-1349 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:1130-1349 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:1130-1349 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:1130-1349 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:1130-1349 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:1130-1349 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:1130-1349 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:1130-1349 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:1130-1349 |

Derived by importer:
- total_premium = 0 always (deliberately ignored) [PCPI:1130-1349]
- contribution type BY_VALUE(0): premium = getPremium(cell,tax); contribution = getPremium(ec,tax) [PCPI:1130-1349]
- contribution type BY_PERCENTAGE(1): x_premium = intval(round(total_cover*cell/100,2)+tax pct) (cell is pct OF COVER); x_employee_contribution = intval(round(x_premium*ec/100,2)) (% of that premium, no extra tax) [PCPI:1130-1349]
- uses isset() so 0 is stored (unlike types 13/15/16); is_claim_rate fixed 0 [PCPI:1130-1349]
- BUG: set_of_parents_in_laws contribution writes double_parent_in_law_premium instead of ..._employee_contribution [PCPI:1130-1349]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:1130-1349; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:1130-1349]; formulas are pre-calculated [PCPI:1130-1349]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:1130-1349; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:1130-1349]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:1130-1349]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample ('Is Family Relation Max Capping' 'N', 'Family Relation' 'E,S,P,PIL', Total Premium blank, Self 10 (%), EC 50 (%))

### 25 - LOL_FLAT

- Sample: `none`
- Importer: none; layout: no-sheet / UNKNOWN; header rows: 0

Notes / validation behaviour:
- 25 is in PremiumType::LOL_RATERS so the controller routes any premium file to PolicyConfiguratorPremiumImport, but that importer has no `elseif` branch for 25 => model() returns null and nothing is stored [constant.php:87,143; PCPI:67-124]
- UNKNOWN: flat LOL premium is probably entered via the form (like type 1); no sample exists

### 26 - LOL_AGE_OF_EMPLOYEE

- Sample: `lol_age_premium.xlsx`
- Importer: PCPI:1350-1543; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Age Band | `age_band` | `age` | string | yes | getAgeBand() | PCPI:1350-1543 |
| Grade | `grade` | `grade_id` | string | no |  | PCPI:1350-1543 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:1350-1543 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no |  | PCPI:1350-1543 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Self | `self` | `self_premium` | number | no | slug read by importer: row['self'] | PCPI:1350-1543 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number | no | slug: row['self_employee_contribution'] | PCPI:1350-1543 |
| Spouse | `spouse` | `spouse_premium` | number | no | slug read by importer: row['spouse'] | PCPI:1350-1543 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number | no | slug: row['spouse_employee_contribution'] | PCPI:1350-1543 |
| Partner | `partner` | `partner_premium` | number | no | slug read by importer: row['partner'] | PCPI:1350-1543 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number | no | slug: row['partner_employee_contribution'] | PCPI:1350-1543 |
| Per Kid | `per_kid` | `kids_premium` | number | no | slug read by importer: row['per_kid'] | PCPI:1350-1543 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number | no | slug: row['per_kid_employee_contribution'] | PCPI:1350-1543 |
| Per Parent | `per_parent` | `single_parent_premium` | number | no | slug read by importer: row['per_parent'] | PCPI:1350-1543 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number | no | slug: row['per_parent_employee_contribution'] | PCPI:1350-1543 |
| Per Parents In Law | `per_parents_in_law` | `single_parent_in_law_premium` | number | no | slug read by importer: row['per_parents_in_law'] | PCPI:1350-1543 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number | no | slug: row['per_parent_in_law_employee_contribution'] | PCPI:1350-1543 |
| Set of Parents | `set_of_parents` | `double_parent_premium` | number | no | slug read by importer: row['set_of_parents'] | PCPI:1350-1543 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number | no | slug: row['set_of_parents_employee_contribution'] | PCPI:1350-1543 |
| Set of Parent in Laws | `set_of_parent_in_laws` | `double_parent_in_law_premium` | number | no | slug read by importer: row['set_of_parent_in_laws'] | PCPI:1350-1543 |
| Set of Parent in Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number | no | slug: row['set_of_parent_in_laws_employee_contribution'] | PCPI:1350-1543 |
| Others Premium | `others_premium` | `others_premium` | number | no |  | PCPI:1350-1543 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number | no |  | PCPI:1350-1543 |

Derived by importer:
- total_premium ALWAYS 0 (undefined $totalPremiumWithTax) [PCPI:1350-1543]
- BY_VALUE: getPremium(cell,tax); BY_PERCENTAGE: intval(round(total_cover*cell/100,2)+tax pct) and contribution = pct of that premium [PCPI:1350-1543]
- isset() semantics (0 stored)
- BUG same as 24: set_of_parent_in_laws contribution overwrites double_parent_in_law_premium [PCPI:1350-1543]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:1350-1543; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:1350-1543]; formulas are pre-calculated [PCPI:1350-1543]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:1350-1543; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:1350-1543]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:1350-1543]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Sample lol_age_premium.xlsx: only 'Age Band','Total Cover','Self','Self Employee Contribution' headers (rows ['0-50',200000,12000,12000],['51-99',400000,20000,20000])

### 27 - GDAC_WITH_MAX_AGE

- Sample: `gdac_with_max_age_premium_89.xlsx`
- Importer: PCPI:808-1075; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Construct | `construct` | `no_of_adult,no_of_children` | string '2A+1C' | no | types 22,27 only (skipped for 32) | PCPI:808-1075 |
| Grade | `grade` | `grade_id` | string | no |  | PCPI:808-1075 |
| Designation | `designation` | `designation_id` | string | no |  | PCPI:808-1075 |
| Age Band | `age_band` | `age` | string | no | getAgeBand() ('>80','<18','25' conventions) | PCPI:808-1075 |
| Employee Age Band | `employee_age_band` | `employee_age` | string | no | stored verbatim, NOT passed through getAgeBand | PCPI:808-1075 |
| Base Suminsured | `base_suminsured` | `base_suminsured` | number | no | optional | PCPI:808-1075 |
| Family Group | `family_group` | `family_group_id (MasterFamilyGroup)` | string | no | 22/27: comma list of [n]CODE with CODE in E,S,C,P,PIL,PR,SB (single leading digit = count; e.g. 'E,S,2C','E,S,2P,2PIL,SB'); special 'P/PIL(2/3/4)' expands to multiple rates. 32: ordered list of F,M,FIL,MIL e.g. 'F, MIL' | PCPI:808-1075 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:808-1075 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Self Premium | `self_premium` | `self_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Spouse Premium | `spouse_premium` | `spouse_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Kid Premium | `per_kid_premium` | `kids_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Parent Premium | `per_parent_premium` | `single_parent_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Parent In Law Premium | `per_parent_in_law_premium` | `single_parent_in_law_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Set of Parents Premium | `set_of_parents_premium` | `double_parent_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Set of Parent In Laws Premium | `set_of_parent_in_laws_premium` | `double_parent_in_law_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Set of Parent In Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Others Premium | `others_premium` | `others_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Partner Premium | `partner_premium` | `partner_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Cross Parent Premium | `cross_parent_premium` | `cross_parent_premium` | number | no |  | PCPI:808-1075 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:808-1075 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no |  | PCPI:808-1075 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:808-1075 |

Derived by importer:
- tax column stored NULL, total_premium stored 0 (always); comment: 'Tax logic added in Rater Helper & Facade for this Rater' [PCPI:808-1075]
- premium/contribution cells copied RAW (no tax, no pct conversion) - contribution type ignored [PCPI:808-1075]
- family group: MasterFamilyGroup::firstOrCreate on member counts [22/27 keyed WITHOUT the string; 32 keyed WITH family_group string] [PCPI:808-1075]
- 'P/PIL(4)' => rates for P,PIL,[P,PIL],2P,2PIL,[P,2PIL],[2P,PIL],[2P,2PIL]; 'P/PIL(3)' => first 7; 'P/PIL(2)' => first 5; one MasterPolicyRates row per expansion [PCPI:1548-1670]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:808-1075; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:808-1075]; formulas are pre-calculated [PCPI:808-1075]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:808-1075; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:808-1075]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:808-1075]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Family group token parse only reads first char as count (so '10C' unsupported); empty token => PHP offset error [PCPI:808-1075]
- If both 'family_group' cell empty: no family_group_id stored
- PremiumType::GDAC_RATERS=[22,27,32] [constant.php:141]
- Same branch as 22 (P:808). Sample header: Construct, Grade, Designation, Age Band, Family Group, Total Cover, Self Premium... ; age bands '35-55','56-70','71-120' [sample]
- 'max age' semantic UNKNOWN beyond Age Band column

### 28 - FAMILY_PERCENTAGE_WISE

- Sample: `family_percentage_premium_sample.xlsx`
- Importer: NPRS:584-629; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", null, 500000, 500000]`
  - row2: `["Relation", "Age", "Family Premium(%)", "Member Premium(%)"]`
  - row3+: `["Self", "0-100", 10, null]`
  - more: `["Son", "30-100", null, 100]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Relation (col A) | `Self`, `Spouse`, `Son`, `Daughter`, `Brother`, `Sister`, `Mother`, `Father`, `Mother-in-law`, `Father-in-law` | `relation_id` | string | yes | getRelationId(): exact RelationMaster.name; 'Spouse' fallback 'Spouse/Partner'; else NULL | NPRS:584-629 |
| Age (col B) | `Age` | `age` | string | yes | stored verbatim e.g. '0-100', '0<29', '30-100' | NPRS:584-629 |
| Family Premium(%) | - | `total_premium + is_family_premium=1` | number % | no | header EXACT 'Family Premium(%)'; cell stored as percent in total_premium (no tax applied) | NPRS:584-629 |
| Member Premium(%) | - | `total_premium + is_member_premium=1` | number % | no | header EXACT 'Member Premium(%)' | NPRS:584-629 |
| Suminsured (row 1 over the % column) | - | `flat_suminsured` | number | yes | cell skipped if row-1 SI cell is empty | NPRS:584-629 |

Derived by importer:
- tax stored; NO tax applied to the percentage
- is_family_premium / is_member_premium booleans

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:584-629]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:584-629]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]
- First two columns skipped as labels; cell must be isset (empty skipped)
- Can Add Parents columns not used

### 29 - GRADE_WITH_BASE_SI

- Sample: `grade_with_base_si_premium_rater_sample_94.xlsx`
- Importer: NPRS:630-681; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", null, 100000, 200000, 300000]`
  - row2: `["Base SI", "Grade", "Premium", "Premium", "Premium"]`
  - row3+: `[300000, "AVP and Below", 5473, 7115, 9249]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Base SI (col A) | `Base SI` | `base_suminsured` | number | yes |  | NPRS:630-681 |
| Grade (col B) | `Grade` | `grade_id` | string | yes | addGrade() | NPRS:630-681 |
| Premium | `Premium` | `grade_premium (raw) + total_premium (taxed)` | number | yes | header EXACT 'Premium' (capital P; lowercase not accepted) | NPRS:630-681 |
| Top-up Suminsured (row 1) | - | `top_up_suminsured` | number | yes | SI over each Premium column | NPRS:630-681 |

Derived by importer:
- tax>0 : total_premium = premium + premium*tax/100 ; else tax stored NULL and total_premium = (float)premium [NPRS:630-681]
- premium column stores RAW (pre-tax) cell; total_premium stores taxed value

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:630-681]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Optional header cells in row 2 exactly 'Can Add Parents' and 'Can Add Parents In Law' (case/space exact, note plural; samples do not include them); value 'NO' or 'N' (any case) => 0, anything else => 1; if header absent => 1 [NPRS:630-681]
- Rows are bulk inserted with MasterPolicyRates::insert after SET FOREIGN_KEY_CHECKS=0 (no model events) [NPRS:1052-1054]
- No validation errors are raised by the importer; malformed sheets fail with PHP ErrorException (undefined offset) => controller returns {status:false,message:'Please upload proper excel file',log} [controller:3082-3083]
- Existing non-flat rates are deleted first: MasterPolicyRates where policy_id AND total_premium NOT NULL AND is_opd_rate=0 AND is_enchance_flex_rate=0 AND is_exceptional_rate=0 (for type 17: relation_permilly NOT NULL instead) [controller:3048-3051; OPD 3131-3135]

### 30 - AGE_PLUS_RELATION_WISE

- Sample: `age_plus_relation_wise_premium_rater_sample.xlsx`
- Importer: NPRS:682-926; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", null, 300000, null, 500000, null, 700000]`
  - row2: `["Relation", "Age", "Premium", "Employee Contribution", "Premium", "Employee Contribution", "Premium", "Employee Contribution"]`
  - row3+: `["Self", "0-25", 4355, 4355, 5000, 5000, 7000, 7000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Relation (col A) | `Self`, `Spouse`, `Son`, `Daughter`, `Father`, `Mother`, `Father-in-law`, `Mother-in-law` | `relation_id` | string | yes | getRelationId() exact name | NPRS:682-926 |
| Age (col B) | `Age` | `age` | string 'a-b' | yes | verbatim | NPRS:682-926 |
| Premium | `Premium` | `<rel>_premium` | number | yes | header EXACT 'Premium' | NPRS:682-926 |
| Employee Contribution (col after Premium) | `Employee Contribution` | `<rel>_employee_contribution` | number | no | header text not checked; whichever cell follows Premium | NPRS:682-926 |
| Suminsured (row 1 above Premium col) | - | `<rel>_suminsured` | number | yes |  | NPRS:682-926 |

Derived by importer:
- x_premium = addTax(premium, tax) (taxed); total_premium = taxed premium; relation-specific *_suminsured = SI above the Premium column [NPRS:682-926]
- employee contribution = cell IMMEDIATELY RIGHT of the Premium cell; blank => 0 [NPRS:682-926]
- BY_VALUE policy: contribution = addTax(ec, tax); BY_PERCENTAGE: ec stored raw (percentage) [NPRS:682-926]
- contribution type read from the saved policy via PolicyDTO (ipd/opd), NOT from the upload request; if policy is neither IPD nor OPD $byValue undefined [NPRS:682-926]
- relation->column map: Self->self_*; Spouse->spouse_*; Son/Daughter->kids_* (+per_kid_employee_contribution); Brother/Sister->others_*; Partner->partner_*; Father/Mother->single_parent_*; Father-in-law/Mother-in-law->single_parent_in_law_*; each with *_suminsured, *_premium, *_employee_contribution
- unmatched relation => $data stale from previous cell or undefined [NPRS:682-926]

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:682-926]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Can Add Parents columns not used by this branch
- Rows inserted bulk [NPRS:1052]

### 31 - GRAL

- Sample: `gral_premium_rater_sample.xlsx`
- Importer: NPRS:927-1050; layout: matrix; header rows: 2
- Matrix example (rows 1-3 of sample):
  - row1: `["Suminsured", null, null, null, 100000, null, 300000]`
  - row2: `["Relation", "Age", "Gender", "Location", "Premium", "Employee Contribution", "Premium", "Employee Contribution"]`
  - row3+: `["Self", "18-25", "Male", "Behrin", 5000, 5000, 10000, 10000]`

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Relation (col A) | `Self`, `Spouse`, `Son`, `Daughter`, `Brother`, `Sister`, `Partner`, `Father`, `Mother`, `Father-in-law`, `Mother-in-law` | `relation_id` | string | yes |  | NPRS:927-1050 |
| Age (col B) | `Age` | `age` | string | no |  | NPRS:927-1050 |
| Gender (col C) | `Gender` | `employee_gender` | string 'Male'/'Female' | no | stored verbatim | NPRS:927-1050 |
| Location (col D) | `Location` | `location` | string | no | stored verbatim (e.g. 'Behrin','India','UAE'); may be blank | NPRS:927-1050 |
| Premium | `Premium` | `<rel>_premium` | number | yes | header EXACT 'Premium'; first 4 columns skipped | NPRS:927-1050 |
| Employee Contribution (next col) | `Employee Contribution` | `<rel>_employee_contribution` | number | no |  | NPRS:927-1050 |
| Suminsured (row 1) | - | `<rel>_suminsured` | number | yes |  | NPRS:927-1050 |

Derived by importer:
- x_premium = addTax(premium, tax) (taxed); total_premium = taxed premium; relation-specific *_suminsured = SI above the Premium column [NPRS:927-1050]
- employee contribution = cell IMMEDIATELY RIGHT of the Premium cell; blank => 0 [NPRS:927-1050]
- BY_VALUE policy: contribution = addTax(ec, tax); BY_PERCENTAGE: ec stored raw (percentage) [NPRS:927-1050]
- contribution type read from the saved policy via PolicyDTO (ipd/opd), NOT from the upload request; if policy is neither IPD nor OPD $byValue undefined [NPRS:927-1050]
- relation->column map: Self->self_*; Spouse->spouse_*; Son/Daughter->kids_* (+per_kid_employee_contribution); Brother/Sister->others_*; Partner->partner_*; Father/Mother->single_parent_*; Father-in-law/Mother-in-law->single_parent_in_law_*; each with *_suminsured, *_premium, *_employee_contribution
- unmatched relation => $data stale from previous cell or undefined [NPRS:927-1050]

Notes / validation behaviour:
- Importer is ToCollection (no WithHeadingRow): row index 0 = SI row, index 1 = header row, data from index 2; header slugs are NOT used [NPRS:927-1050]
- Rows whose first cell (col A) is empty are dropped before processing; if first row has <=1 cell nothing is imported and NO error is raised [NPRS:65-72]
- Rows inserted bulk [NPRS:1052]

### 32 - GDAC_WITH_SEQUENCE

- Sample: `gdac_with_sequence_premium_rater_sample.xlsx`
- Importer: PCPI:808-1075; layout: heading-row table; header rows: 1

| header | accepted as | fills (master_policy_rates) | type | required | note | cite |
|---|---|---|---|---|---|---|
| Grade | `grade` | `grade_id` | string | no |  | PCPI:808-1075 |
| Designation | `designation` | `designation_id` | string | no |  | PCPI:808-1075 |
| Age Band | `age_band` | `age` | string | no | getAgeBand() ('>80','<18','25' conventions) | PCPI:808-1075 |
| Employee Age Band | `employee_age_band` | `employee_age` | string | no | stored verbatim, NOT passed through getAgeBand | PCPI:808-1075 |
| Base Suminsured | `base_suminsured` | `base_suminsured` | number | no | optional | PCPI:808-1075 |
| Family Group | `family_group` | `family_group_id (MasterFamilyGroup)` | string | no | 22/27: comma list of [n]CODE with CODE in E,S,C,P,PIL,PR,SB (single leading digit = count; e.g. 'E,S,2C','E,S,2P,2PIL,SB'); special 'P/PIL(2/3/4)' expands to multiple rates. 32: ordered list of F,M,FIL,MIL e.g. 'F, MIL' | PCPI:808-1075 |
| Total Cover | `total_cover` | `total_cover` | number | yes |  | PCPI:808-1075 |
| Claim Rate | `claim_rate` | `is_claim_rate` | Yes/No | no | Yes,Y,yes,y,1,'1' => 1 else 0 | PCPI:140-145 |
| Copayment Rate | `copayment_rate` | `is_copayment_rate` | Yes/No | no | same accepted values; ONLY read if truthy | PCPI:1793-1815 |
| Copayment Percentage | `copayment_percentage` | `copayment_percentage` | int | no | (int) cast; only read when copayment_rate is truthy | PCPI:1793-1815 |
| Can Add Parent | `can_add_parent` | `can_add_parent` | Y/N | no | Y or YES (any case) => 1; ANY other non-null text => 0; column absent => 1 | PCPI:112-118 |
| Can Add Parent In Laws | `can_add_parent_in_laws` | `can_add_parent_in_laws` | Y/N | no | same rule; slug must be can_add_parent_in_laws | PCPI:120-126 |
| Self Premium | `self_premium` | `self_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Self Employee Contribution | `self_employee_contribution` | `self_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Spouse Premium | `spouse_premium` | `spouse_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Spouse Employee Contribution | `spouse_employee_contribution` | `spouse_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Kid Premium | `per_kid_premium` | `kids_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Kid Employee Contribution | `per_kid_employee_contribution` | `per_kid_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Parent Premium | `per_parent_premium` | `single_parent_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Parent Employee Contribution | `per_parent_employee_contribution` | `single_parent_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Per Parent In Law Premium | `per_parent_in_law_premium` | `single_parent_in_law_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Per Parent In Law Employee Contribution | `per_parent_in_law_employee_contribution` | `single_parent_in_law_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Set of Parents Premium | `set_of_parents_premium` | `double_parent_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Set of Parents Employee Contribution | `set_of_parents_employee_contribution` | `double_parent_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Set of Parent In Laws Premium | `set_of_parent_in_laws_premium` | `double_parent_in_law_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Set of Parent In Laws Employee Contribution | `set_of_parent_in_laws_employee_contribution` | `double_parent_in_law_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Others Premium | `others_premium` | `others_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Others Employee Contribution | `others_employee_contribution` | `others_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Partner Premium | `partner_premium` | `partner_premium` | number (raw) | no | stored without tax or contribution conversion | PCPI:808-1075 |
| Partner Employee Contribution | `partner_employee_contribution` | `partner_employee_contribution` | number (raw) | no |  | PCPI:808-1075 |
| Cross Parent Premium | `cross_parent_premium` | `cross_parent_premium` | number | no |  | PCPI:808-1075 |
| Cross Parent Employee Contribution | `cross_parent_employee_contribution` | `cross_parent_employee_contribution` | number | no |  | PCPI:808-1075 |
| All Parent Premium | `all_parent_premium` | `all_parent_premium` | number | no |  | PCPI:808-1075 |
| All Parent Employee Contribution | `all_parent_employee_contribution` | `all_parent_employee_contribution` | number | no |  | PCPI:808-1075 |

Derived by importer:
- tax column stored NULL, total_premium stored 0 (always); comment: 'Tax logic added in Rater Helper & Facade for this Rater' [PCPI:808-1075]
- premium/contribution cells copied RAW (no tax, no pct conversion) - contribution type ignored [PCPI:808-1075]
- family group: MasterFamilyGroup::firstOrCreate on member counts [22/27 keyed WITHOUT the string; 32 keyed WITH family_group string] [PCPI:808-1075]
- 'P/PIL(4)' => rates for P,PIL,[P,PIL],2P,2PIL,[P,2PIL],[2P,PIL],[2P,2PIL]; 'P/PIL(3)' => first 7; 'P/PIL(2)' => first 5; one MasterPolicyRates row per expansion [PCPI:1548-1670]

Notes / validation behaviour:
- Importer is ToModel+WithHeadingRow: row 1 = headings, data from row 2; heading cells are slugged (config/excel.php:60 formatter='slug' => Str::slug($h,'_') by maatwebsite/excel 3.1, vendor not present locally so exact slug fn is library behaviour [UNKNOWN-in-repo]); only the slug is matched, so 'Self', 'self', ' SELF ' all work [PCPI:808-1075; config/excel.php:56-60]
- SkipsEmptyRows + chunk size 10 [PCPI:808-1075]; formulas are pre-calculated [PCPI:808-1075]
- No explicit validation error is thrown anywhere in this importer; wrong/missing headers silently produce NULL/0 values; PHP exceptions (e.g. $row['grade'] undefined index for type 15/16 if the header is absent) return controller {status:false,message:'Please upload proper excel file'} [PCPI:808-1075; controller:3082-3083]
- Premium cells: truthy-test @$row['x'] means 0/blank/'0' skip the column entirely (stays NULL) for types 13/15/16/19/21 [PCPI:808-1075]
- Rate rows are created one at a time via MasterPolicyRates::create (model events fire) [PCPI:808-1075]
- Delete-before-insert: policy rates with total_premium NOT NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (OPD: is_opd_rate=1) [controller:3048-3051,3131-3135]
- Second 'individual' upload (in_premium_file, only when si_type=3 INDIVIDUAL_AND_FAMILY_COVER): same importer with cover_type=1; the main premium_file then gets cover_type=2 (FAMILY_COVER) [controller:3064-3069,3091-3111; constant.php:30-31]
- Routed here when premium type in PremiumType::CONSTRUCT_RATERS [13,15,16,22,24,27,32] or AGE_RELATION_RATERS [19,21] or LOL_RATERS [23,24,25,26] [constant.php:130-143; controller:3064-3071,3148-3155]
- Family group token parse only reads first char as count (so '10C' unsupported); empty token => PHP offset error [PCPI:808-1075]
- If both 'family_group' cell empty: no family_group_id stored
- PremiumType::GDAC_RATERS=[22,27,32] [constant.php:141]
- Construct column ignored for 32 [PCPI:808-1075]
- Family Group is an ORDERED sequence of F,M,FIL,MIL (whitespace removed, upper-cased, e.g. 'F, MIL' => 'F,MIL'): parents/parents-in-law only; relation ids kept in sequence order (not sorted) [PCPI:808-1075]
- Sample header: Grade, Designation, Family Group, Employee Age Band, Total Cover, Self Premium, ... ; 64 rows enumerate all permutations of F/M/FIL/MIL

## 5. PolicyCustomRateImport (top-up / base-SI custom rate sheets)

- File: `app/Imports/PolicyCustomRateImport.php`. Use: SI/premium 'custom rate' sheets for top-up policies (si_file / in_suminsured_file / topup_si_file / opd_si_file) [controller:2776,2885,5179-5406]
- `base-on-base-si-and-premium-sample.xlsx`: row1: col B='Top-up Factor' (then label), row2: col A='Base SI / Deductible', col B=<factor name>, col C..=numeric top-up SI; data rows: col A base SI, col B factor value, then premiums. Factor value in row2 col B selects: 'Age','Grade','Designation','Family Construct','Family Group' [PolicyCustomRateImport.php:54-110]
- Derived: total_premium=addTax(premium,tax); zero/blank premium skipped; columns base_suminsured, top_up_suminsured, age|grade_id|designation_id|no_of_adult/no_of_children|family_group_id [PolicyCustomRateImport.php:56-107]
- Other base SI sub types: for FLAT/AGE/GRADE... base SI sub types: rows labelled 'suminsured' hold top-up SI; premium rows hold age/grade in col A, base SI in col B [PolicyCustomRateImport.php:200-320]; full detail of GRADE/DESIGNATION/STATE branches UNKNOWN (not read)

## 6. Known importer quirks (carry into any rewrite deliberately or fix)

- Types 19, 21, 26 always store `total_premium = 0` (undefined `$totalPremiumWithTax`, PCPI:647-807, 1350-1543). Type 24 stores 0 deliberately (PCPI:1130-1349). Type 16 stores `Total Premium` untaxed while 13/15 add tax.
- Types 24 and 26: the `set_of_parent_in_laws_employee_contribution` branch writes `double_parent_in_law_premium` instead of `double_parent_in_law_employee_contribution` (PCPI:1323-1327 and 1518-1522).
- PCPI types 13/15/16/19/21 ignore zero premium cells (truthy test), LOL types 24/26 store zeros (`isset`).
- Type 15/24 `strlen($row['family_relation'] >= 1)` is always truthy; `family_relation` is only split on commas and mapped via E,S,C,P,PIL,PR,SB (PCPI:351-356, 1750-1791).
- `grade_plus_family_construct_premium_sample_65.xlsx` header `Per Parent In Law` slugs to `per_parent_in_law`, which is not read (importer expects `per_parents_in_law`).
- NPRS 20/30/31: unrecognised relation text leaves `$relationIds` / `$data` stale from the previous row (or undefined on the first row) instead of failing cleanly.
- NPRS 30/31 `$byValue` is undefined if the policy is neither IPD nor OPD (NPRS:683-692, 934-937).
- GDAC 22/27: family-group counts use only the first character of a token (so `10C` is unsupported) and an empty token causes an offset error (PCPI:946-1066).
