# Legacy endorsement sheet formats: ADD, REMOVE, UPDATE and the enrollment report (research, read-only)

Source tree: `/home/user/udogg/wrapper/eb-backend_copy` (legacy Laravel "eb-backend"). Paths below are relative to it. Machine-readable twin: `endorsement-sheet-formats.json` (same directory; shape `{format: {variant: {columns:[{label, field, mandatory, mandatory_when, note}]}}}`).
Citations are `file:line`; ranges for large blocks are approximate (+-5 lines). **UNKNOWN** = not provable from the code in this tree. This document supersedes the "UNKNOWN: seeder not uploaded" parts of `enrollment-report-format.md` (the seeders ARE in this tree).

## 0. Headline findings

1. **Header text lives in seeders, not in code.** ADD = `default_endrosement_format_id` 1, REMOVE = 2, UPDATE (correction) = 3 in table `default_endrosement_format_feilds_mappings` (model `app/Models/DefaultEndrosementFormatFeildsMapping.php`; format names `database/seeds/DefaultEndrosementFormatSeeder.php:17-37`). Rows come from `AdditionEndorsementColumnMappingSeeder.php` (format 1), `CorrectionEndorsementColumnMappingSeeder.php` (3), `DeletionEndorsementColumnMappingSeeder.php` (2) plus small additions in `DefaultEndrosementFormatFeildsMappingSeeder.php` (ids 3413/3414/3443-3445/3496-3499/4518-4521), `MiscEndorsementFieldsSeeder.php`, `LolEndrosementAdditionSeeder.php`, `DefaultEndorsementInsurerEndorsementFields.php`, `DefaultEndorsementSheetEndorsementFields.php` (called from `DefaultEndrosementFormatFeildsMappingSeeder.php:30-34, 30398-30399, 31255, 33370`).
2. **The default sample is "all active rows ordered by `sequence`"** (`SampleFileController.php:481-486`), minus the 48 names in `getNewTataFields()` (`app/Helpers/helper.php:9068-9097`). Header sets do NOT change with policy type, rater, IPD/OPD or TPA. What changes per policy: (a) `Employer Name` is dropped for ADD/UPDATE when there are no child entities (`SampleFileController.php:497-515`), (b) `Copayment`/`Copayment Percentage` dropped for ADD when no copayment rate (`:525-545`), (c) dropdown lists (grade, designation, SI, relation, ...) are filled from the policy (`:127-389`), and (d) which cells are *mandatory* is decided by the validators (sections 3-5), not by the seeded `is_mandatory` flag.
3. **`is_mandatory` / `feild_validation` in the seeder are informational.** `EndrosementDumpImport::rules()` returns `[]` (`app/Imports/Endrosement/EndrosementDumpImport.php:617`); the real rules are in `EndorsementAdditionValidation`, `EndorsementRemovalValidation`, `EndorsementCorrectionValidation` (`app/Services/Endorsement/Validation/`).
4. **Policies may override the whole header list** with a custom template (`CustomFormatController.php:12-60`, tables `custom_bulk_upload_templates` / `policy_custom_upload_template_mappings`); those rows exist only in the DB so such header sets are UNKNOWN. The importer re-maps custom labels to field names by slug (`EndrosementDumpImport.php:60-102`).
5. **Header matching is by slug** (`HeadingRowFormatter::default("slug")`, `EndrosementDumpImport.php:32`): case/space/punctuation-insensitive, "/" dropped. Load-bearing spellings: `Endrosement Number`, `Inception/Endorsement Date` (-> `inceptionendorsement_date`), `Add/delete` (-> `adddelete`, patched at `EndorsementDumpImportJob.php:179,224-226`).
6. **The enrollment report that is actually executed is NOT the `app/Modules/Reports/EnrollmentReports/*` code** (dead behind `Portal::check(EMPLOYEE_BENEFIT) && false`, `app/Jobs/EnrollmentReportingJob.php:111,123`; `OpdEnrollmentReportJob.php:68`). Live: Consolidated -> `EntityEnrollmentReportingService` (field catalog = format 32, `EntityExportFeildsSeeder.php`), Standalone -> `StandaloneEnrollmentReportingService` (headers derived from row keys), OPD -> `OPDEnrollmentReportService`. Section 6.
7. **Member identifier:** `employee_members.endorsement_id` (8 random letters `A-Za-z` without I/i, `helper.php:965-969`, assigned on create `EmployeeMembers.php:65`). In the sheets it is `Endrosement Number` (`endrosement_number`); in the live reports it is the column `Endorsement Number` (`endorsement_number`, `StandaloneEnrollmentReportingService.php:384,1485`; `EntityEnrollmentReportingService.php:1266`). Header spelling differs, so a report is not directly re-uploadable (section 7).

## 1. Pipeline: download, upload, validate, process

* Download sample: `SampleFileController::getEndorsementSampleDocument` (`?type=1|2|3`, `policy_id`, optional `broker_id`; `:62-70`). File = `EndorsementTemplateExport` (header row + `EXCEL_FORMATTED_ROWS`=50 rows with list validations and `dd/mm/yyyy` date cell formats; `EndorsementTemplateExport.php:691-722`; a dropdown whose joined values exceed 255 chars is emitted empty, `:693-697`).
* Upload: `EndrosementEnrollementController::newEndroseMembers` (`:936-1105`). Params `employer_id`, `policy_id`, `type`, `member_sheet` (xls/xlsx/csv), `is_inception`, `to_process`, `to_backup`, `should_override`, `update_data_in_all_policies`, `hr_consent`. **Upload type codes are 3=add, 4=remove, 5=update** (stored in `excel_error_reports.document_type_id`; the importer maps them to `type_id` 1/2/3, `EndrosementDumpImport.php:519-526`; the "sample download" `type` is 1/2/3. The success messages at `:1081-1100` are keyed on 3/4/5). Do not mix the two numbering schemes.
* `EndorsementDumpImportJob` (`app/Jobs/EndorsementDumpImportJob.php`) reads the sheet with `EndrosementDumpImport` into `endrosement_data_dumps` (+ `_two` for the Tata two-column fields), then dispatches: add -> `endrosementJobCall` (`OptimizedMemberEndorsementJob`, Self rows first then dependants), remove -> `OptimizedRemoveJob` (`app/Jobs/Endorsement/`), update -> `OptimizedUpdateEndorsementJob` (`updationCallJob`). On AIG/EB portals a workbook with sheets named exactly `addition` and/or `removal` is split first (`CombinedEndorsementService.php:18-19,48-70`; `EndorsementDumpImportJob.php:73-93`). Removal sheet chunks of 300 rows, others 500 (`:112-117`).
* Header pre-processing in `EndrosementDumpImport::prepareForValidation` (`:44-530`): for non-Self rows `Employee <x>` columns are copied to `Insured Member <x>` when the sheet lacks the latter (first/last name, dob, gender, email, mobile, state, city, address, pin code, number-of-time-salary; `:188-340`); Self rows the other way. For UPDATE (type 5) the source column is then nulled. `inceptionendorsement_date` defaults to today (`:334-336`). Date columns are normalised to `d-m-Y` (DOB/DOJ) or `Y-m-d` (others). `has_death_certificate`: `Yes`/`Y`/`y` -> 1 else 0 (`:436-445`). `relationship_with_employee` -> `relation_id` map (`:528-592`): Self 1, Spouse 2, Spouse/Partner 2, Daughter 3, Son 4, Father 5, Mother 6, Father-in-law 7, Mother-in-law 8, Siblings 9, Partner 10, Brother 12, Sister 15, Grand Father 20, Grand Mother 21, Uncle 22, Aunt 23, Partner-LGBTQ 25 (names in `MasterRelationSeeder.php`; "Other" 99, "Nephew" 24 exist but are not in the map). The validators then look the label up again by exact `RelationMaster.name`, so use `Spouse` not `Spouse/Partner`.

### 1.1 Cross-cutting value rules

| Topic | Rule | Cite |
|---|---|---|
| Dates (all) | Accepted: Excel serial number, `d/m/Y`, `d-m-Y`, `Y-m-d`, `Y/m/d`, `d.m.Y`, then any `Carbon::parse` string (so `m/d/Y` such as "10/15/2020" is mis-read or rejected). Pure letters rejected. Sample cells are formatted `dd/mm/yyyy` but text is fine. | helper.php:3229-3273; EndorsementTemplateExport.php:719-722 |
| DOB / DOJ day-month-year = 0 | "Invalid date" is stored if any component equals 0 (e.g. 00-01-1990). | helper.php:6861-6887; EndrosementDumpImport.php:480-514 |
| DOB | Required (Self: employee_dob; others: insured_member_dob). No future dates. Self: employee_dob must equal insured_member_dob if both given; employee DOB must differ from DOJ. Age is evaluated at max(policy start, employee DOJ) against `policy_age_details` min/max (continuation age allowed when the member existed in an earlier policy of the same sub-type); adults (Self, Spouse, Partner, parents, in-laws) with no min/max need >= 18 years. Child DOB after employee DOB; parent / in-law DOB before employee DOB; optional `age_difference` from Self or Spouse; `min_age_months` for infants via `calculateCoverStartdate`. | AV:1777-1780, 1850-1895, 1553-1590, 2793-2809 |
| DOJ | Required for Self. Must be <= policy end date. If `policy.endorsement_days_post_doj` > 0, \|today - DOJ\| must not exceed it (AIG feature). | AV:446-469, 2774-2778 |
| Date of marriage (DOM) 18-year rule | For Self and Spouse rows: DOM must be after DOB and DOM - DOB >= 18 full years ("DOB and DOM difference must be atleast 18 years."); DOM must not be in the future; Spouse DOM must equal Self DOM. UPDATE applies the same checks against stored DOBs (both Self and Spouse) and then overwrites the spouse marriage date. | AV:2729-2757, 1612-1630; CV:1287-1335, 432-475 |
| Effective date | Optional. ADD: must lie in [policy start, policy end]; must be >= employee DOJ when DOJ is on the row; if no DOJ and the employee already has an addition_effective_date it must be identical. UPDATE: same range check, saved as `correction_effective_date`. REMOVE: must be <= employee_leaving_date; saved as `deletion_effective_date`. | AV:491-521; CV:518-538; RV:107-120 |
| Inception/Endorsement date | Optional everywhere; blank = today; must be a valid date. | EndrosementDumpImport.php:334-336; AV:473-480; RV:122-127 |
| Gender | Exact set `male, Male, female, Female, Other, other` (M/F/MALE are NOT accepted by the validator even though the job normalises them). Relation-gender consistency: Son, Father, Father-in-law, Grand Father, Brother, Uncle cannot be Female; Daughter, Mother, Mother-in-law, Grand Mother, Sister, Aunt cannot be Male. | AV:1201-1220, 2829-2837; CV:719-737 |
| Names | Regex `/^[a-zA-Z.  ]*$/` by default; `/^[a-zA-Z\/\-".,`\' ]*$/` on EB/TATA/KMD portals. Child with same first name and relation already in the policy is rejected (non-AIG-prod). | AV:138-142, 1360-1430 |
| Mobile | Exactly 10 characters, digits only (employee, insured member, nominee, alternate). Alternate must differ from primary. Country code (`Mobile No Code`) must be an active `master_countries.mobile_code`. | AV:2839-2849, 1256-1264, 2908-2919, 183-191 |
| Email | Required for Self. Regex (EB/EDME stricter: `^[a-zA-Z0-9+_.-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`), no spaces, disposable domains rejected, unique in the sheet, not owned by another employee code / organisation. | AV:131-135, 144-149, 822-996, 2762-2772 |
| Employee code | Regex `/^[a-zA-Z0-9 -_\/]*$/` (note the range " -_"); trimmed; unique per employer; unique in sheet; email-code pairing enforced (`should_override` may re-point an existing code/email). | AV:143, 561-585, 796-996 |
| Yes/No cells | VIP, Should Verify Email, Is Dummy/To Hide Email/Mobile, Copayment, Has Death Certificate, Is Demo Email, Is Password Reset: dropdown Yes/No; code tolerates Yes/YES/Y/y and No/NO/N/n (Is Password Reset validated against exactly those 8; `Member Existing Claim` and `Submit Apeal Yn` use NO/YES, Yes/No/YES/NO). | SFC:355-366; AV:193-199 |
| Unknown headers | Header slugs that are not columns of `endrosement_data_dumps` / `_twos` abort the entire sheet with status INVALID_SHEET (model `$guarded = []` + `create()`); there is no per-column tolerance. Empty sheet -> EMPTY_SHEET. | EndorsementDumpImportJob.php:100-110, 218, 254, 272-292 |
| Policy config that changes mandatory cells | Rater type -> IPD when rater in {IPD(1), IPD+OPD(3), BASE+TOP_UP(4), BASE_ENHANCE_FLEX(5)}, OPD when in {OPD(2), IPD+OPD(3)} (`PolicyDTO.php:41-50`). Suminsured sub-type / premium type decide grade, designation, salary, SI, age and cover-type columns (section 2.3). | app/DTOs/Policy/PolicyDTO.php; constant.php:27-184 |

Abbreviations: AV = `app/Services/Endorsement/Validation/EndorsementAdditionValidation.php`, RV = `.../EndorsementRemovalValidation.php`, CV = `.../EndorsementCorrectionValidation.php`, SFC = `app/Http/Controllers/SampleFileController.php`.

## 2. ADD sheet (format id 1, "Member Addition")

One row per member (Self row + one row per dependant, all carrying the Self `Employee Code`). Self rows are processed before dependants; a dependant whose Self is neither in the policy nor in the sheet fails with "self not found in policy" (AV:257). Seeder sources: `AdditionEndorsementColumnMappingSeeder.php:21-978` plus rows listed in section 0 item 1. Order = `sequence` then id (ties are DB-order: UNKNOWN).

### 2.1 Default header list (resolved: single entity, no copayment rate, no portal-gated rows) - 103 columns

| # | Header label (exact) | Field | Mand. | Condition / seed flag | Value format & validation (file:line) |
|---|---|---|---|---|---|
| 1 | `Employee Code` | `employee_code` | YES |  | Required every row (AV:573). Regex /^[a-zA-Z0-9 -_\/]*$/ (:561,576; " -_" is a char RANGE 0x20-0x5F so most punctuation passes). Trimmed (:569). Stored in a varchar(50) (migration 2025_01_18_111443). EB/Alliance SBI employer ids: must be 11-digit numeric (AV:566-585). Dependants must carry their Self row's code; Self missing in policy and sheet -> "self not found in policy" (AV:257) / "Employee not found." (:1267). |
| 2 | `Employee First Name` | `employee_first_name` | cond. | relationship = Self | Self rows required (validateEmployeeData EMP.5, AV:2811-2819). Name regex /^[a-zA-Z.  ]*$/ (default) or /^[a-zA-Z\/\-".,`' ]*$/ on EB/TATA/KMD portals (AV:138-142). If the sheet has Employee First Name but NO Insured Member First Name column and the row is not Self, the value is copied to insured_member_first_name (IMP:188-200). |
| 3 | `Employee Last Name` | `employee_last_name` | - |  | Optional; same name regex (AV:2821-2827). Same copy-to-member rule for dependants (IMP:201-213). |
| 4 | `Employee Gender` | `employee_gender` | cond. | relationship = Self | Self rows required (AV:2829-2837). Allowed exactly: male, Male, female, Female, Other, other. (Job later normalises MALE/M/F; validation does not accept them, AV:2834.) |
| 5 | `Employee DOB` | `employee_dob` | cond. | relationship = Self | Self rows required (AV:2780-2798). Parsed with validDate() (formats d/m/Y, d-m-Y, Y-m-d, Y/m/d, d.m.Y, Excel serial, then Carbon::parse fallback; helper.php:3229-3273); day/month/year component of 0 -> "Invalid date" (IMP:480-514, helper.php:6861). If Insured Member DOB also given it must equal employee dob ("Employee dob and member dob must be same for self", AV:2793-2797). Future date rejected (AV:1777-1780). Employee DOB == DOJ rejected (AV:2800-2809). Age must satisfy policy_age_details min/max for Self (:1850-1895); adults with no min/max need >=18y (AV:1584-1588 age_must_be_18_years). |
| 6 | `Employee Date of Joining` | `employee_date_of_joining` | cond. | relationship = Self | Self rows required (AV:2774-2778). validDate() format; invalid -> "doj ... does not match the format" (AV:446-452). Must be <= policy end_date ("Employee doj must be less than policy end date", AV:454-459). If policy.endorsement_days_post_doj>0, \|today-DOJ\| must be <= that many days (AV:462-469). Cover start for age calc = max(policy start, DOJ) (AV:1783-1830). |
| 7 | `Employee Email` | `employee_email` | cond. | relationship = Self | Self rows required (AV:2762-2772). Pattern /^[a-zA-Z0-9._%+\-']+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/ (EB/EDME portals use stricter /^[a-zA-Z0-9+_.-]+@.../, AV:144-149) skipped on HOWDEN. Disposable-email domains rejected (AV:131-135). Duplicate in sheet (AV:822-840), already used by other employee code/other organisation (AV:858-996). |
| 8 | `Employee Alternate Email` | `employee_alternate_email` | - |  | Optional; no spaces + isEmailValid() (AV:431-443). |
| 9 | `Employee Mobile Number` | `employee_mobile_number` | - |  | Optional; exactly 10 chars and digits only (AV:2839-2849). |
| 10 | `Mobile No Code` | `mobile_no_code` | - |  | Optional; must be in master_countries.mobile_code (status=1) (AV:183-191). Dropdown list in sample (SFC:341). |
| 11 | `Employee Alternate Mobile Number` | `employee_alternate_mobile_number` | - |  | Optional; 10 digits, numeric, must differ from employee_mobile_number (AV:2908-2919). |
| 12 | `Employee Designation` | `employee_designation` | cond. | Self row AND (suminsured sub-type in DESIGNATION_RATERS [4,14] OR premium type in DESIGNATION_RATERS [3,7,16], for IPD or OPD) | AV:1033-1057. Constants constant.php:55-56,159. Sample dropdown = designations present in policy rates (SFC:147-158). |
| 13 | `Employee Grade` | `employee_grade` | cond. | Self row AND (suminsured sub-type in GRADE_RATERS [3,13,12,18,26,23] OR premium type in GRADE_RATERS [4,6,14,15,24,29], for IPD or OPD) | AV:1027-1049. Sample dropdown = grades present in policy rates (SFC:127-146). LOL grade raters (23/26): grade must exist in master grades, else "Grade not found"; SI must exist for grade ("SI Not Covered In Grade") (AV:268-301). |
| 14 | `Employee Annual Salary` | `employee_annual_salary` | cond. | Self row AND suminsured sub-type NUMBER_OF_TIME_SALARY (5) AND policy.calculate_eligibility_from = salary_1 | AV:1064-1070. Numeric if present (AV:2879-2881). |
| 15 | `Employee Variables in Salary` | `employee_variables_in_salary` | - |  | Optional numeric (AV:2888). |
| 16 | `Employee Number of Time Salary` | `employee_number_of_time_salary` | cond. | Self row AND (suminsured sub-type NUMBER_OF_TIME_SALARY (5)) | AV:1084-1105. Value must be one of rates.no_of_times_of_salary (".00" stripped in dropdown, SFC:256-289); for relation-wise raters (sub-type 15 / premium 17) it must exist for relation Self. |
| 17 | `Employee Address` | `employee_address` | - |  | Optional; no validation found (UNKNOWN beyond storage). |
| 18 | `Employee State` | `employee_state` | - |  | Optional; must equal master_state.state_name (AV:2851-2858). |
| 19 | `Employee City` | `employee_city` | - |  | Optional; no validation found in Addition validator (Correction validator checks master_city.city_name, CV:1069-1074). |
| 20 | `Employee Pin Code` | `employee_pin_code` | - |  | Optional; no validation found in Addition validator (Correction validator: 6 digits, CV:1076-1082). |
| 21 | `Suminsured` | `suminsured` | cond. | IPD policy (rater IPD/IPD+OPD/BASE_PLUS_TOP_UP/BASE_ENHANCE_FLEX) with suminsured sub-type FLAT(1) [every row if cover type INDIVIDUAL, Self row only if FAMILY_COVER] or NO_RULE(16) | validateSuminsured/suminsuredIsRequired AV:1635-1658, 2676-2727; value must equal rates.flat_suminsured (is_opd_rate=0) else "Suminsured does not match with policy" (lang validation.invalid_suminsured). Other raters: value not validated here; SI derived from grade/designation/age/construct in the job. Sample dropdown options per rater: SFC:173-211. |
| 22 | `Employee Premium` | `employee_premium` | - |  | No validation or consumption located in the add pipeline (UNKNOWN: appears to be informational). |
| 23 | `Employer Premium` | `employer_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 24 | `OPD Suminsured` | `opd_suminsured` | cond. | OPD policy (rater OPD or IPD+OPD) with OPD sub-type FLAT or NO_RULE (same Self/Individual rule) | AV:1641-1658; matched against rates.flat_suminsured where is_opd_rate=1. Dropdown SFC:212-255. |
| 25 | `OPD Employer Premium` | `opd_employer_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 26 | `OPD Employee Premium` | `opd_employee_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 27 | `Relationship with Employee` | `relationship_with_employee` | YES |  | Required (AV:604-608). Must equal a RelationMaster.name that the policy covers (policy_age_details), else "Relation not covered into policy." (AV:610-613). Trimmed. Label->id map in importer: Self 1, Spouse 2, Spouse/Partner 2, Daughter 3, Son 4, Father 5, Mother 6, Father-in-law 7, Mother-in-law 8, Siblings 9, Partner 10, Brother 12, Sister 15, Grand Father 20, Grand Mother 21, Uncle 22, Aunt 23, Partner-LGBTQ 25 (IMP:528-592). NB the later name lookup RelationMaster::where(name) (MasterRelationSeeder.php) has no "Spouse/Partner", so use "Spouse". Sample dropdown = names of relations in policy age details (SFC:380-382). |
| 28 | `Date of Marriage` | `date_of_marriage` | - |  | Optional. validDate (AV:482-489). For Self/Spouse rows: DOM must be after DOB and DOB->DOM gap >= 18 years ("DOB and DOM difference must be atleast 18 years."), no future DOM (AV:2729-2757, 616-618). Spouse DOM must equal Self DOM ("DOM should be same as Employee.", AV:1612-1630). |
| 29 | `Employee Unit` | `employee_unit` | - |  | Optional; if set must be in units mapped to the employer (AdminUnitWiseHrMapping) (AV:654-660). Dropdown SFC:350-354. |
| 30 | `Insured Member First Name` | `insured_member_first_name` | cond. | non-Self row | AV:1221-1227 required; name regex as above. |
| 31 | `Salutation` | `salutation` | - |  | Optional; dropdown = MasterSalutation names (SFC:334-338); no validation located (UNKNOWN). |
| 32 | `Insured Member Last Name` | `insured_member_last_name` | - |  | Optional; name regex (AV:1243-1249). |
| 33 | `Insured Member Gender` | `insured_member_gender` | cond. | non-Self row | AV:1201-1220. Allowed: male, Male, female, Female, Other, other. Gender/relation consistency: Son/Father/Father-in-law/Grand Father/Brother/Uncle must not be Female; Daughter/Mother/Mother-in-law/Grand Mother/Sister/Aunt must not be Male ("Invalid gender with this relation."). |
| 34 | `Insured Member DOB` | `insured_member_dob` | cond. | non-Self row | AV:1186-1199 required ("Member dob is required."). Child DOB must be after employee DOB; Parent/Parent-in-law DOB before employee DOB (AV:1552-1558); policy age_difference rule (AV:1559-1580); adults need >=18y (AV:1580-1590); min/max age in policy_age_details evaluated at cover start (AV:1850-1895); child min_age_months rule via calculateCoverStartdate (:1926-1935). Required for MEMBER_AGE premium (:1294-1296). Same date parsing/"Invalid date" handling as employee_dob. |
| 35 | `Insured Member Email` | `insured_member_email` | - |  | Optional; pattern + no spaces + isEmailValid (AV:1229-1241). |
| 36 | `Insured Member Mobile Number` | `insured_member_mobile_number` | - |  | Optional; 10 digits numeric (AV:1256-1264). |
| 37 | `Insured Member Number of Time Salary` | `insured_member_number_of_time_salary` | cond. | non-Self row AND (sub-type RELATION_WISE_NUMBER_OF_TIME_SALARY (15) OR premium type 17) | AV:1273-1292; value must exist for that relation in rates. |
| 38 | `Insured Member Address` | `insured_member_address` | - |  | Optional; no validation found. |
| 39 | `Employee No of Time Suminsured` | `employee_no_of_time_suminsured` | - |  | Only meaningful when suminsured sub-type NUMBER_OF_TIME_SUMINSURED (28); dropdown SFC:302-312. No Addition-validator rule found (UNKNOWN); Correction validator requires it when suminsured is supplied (CV:1034-1050). |
| 40 | `Insured Member City` | `insured_member_city` | - |  | Optional; no validation found in Addition validator. |
| 41 | `Insured Member State` | `insured_member_state` | - |  | Optional; must equal master_state.state_name (AV:1250-1255). |
| 42 | `Insured Member Pin Code` | `insured_member_pin_code` | - |  | Optional; no validation found in Addition validator. |
| 43 | `Inception/Endorsement Date` | `inceptionendorsement_date` | - |  | Optional. If blank the importer fills today (Y-m-d) (IMP:334-336). If given must pass validDate (AV:473-480). Header "Inception/Endorsement Date" is slugged to inceptionendorsement_date (the "/" is dropped). |
| 44 | `Effective Date` | `effective_date` | - |  | Optional. validDate; must be within [policy.start_date, policy.end_date]; must be >= employee DOJ when DOJ present; if DOJ absent must equal the existing EPR.addition_effective_date (AV:491-521). |
| 45 | `CTC` | `ctc` | - |  | Regex /^[0-9,]+$/ (AV:165-168). |
| 46 | `Location` | `location` | - |  | EB/TATA: /^[a-zA-Z  ]+$/ else /^[a-zA-Z0-9.,  ]+$/ (AV:156-160, 169-175). |
| 47 | `Zone` | `zone` | - |  | Dropdown = ZoneMaster.zone (SFC:379); no validation located. |
| 48 | `Is VIP Employee` | `is_vip_employee` | - |  | Yes/No dropdown (SFC:355); job accepts Yes/YES/Y/y and No/NO/N/n (OptimizedMemberEndorsementJob.php:658-662). |
| 49 | `Is Demo Email` | `is_demo_email` | - |  | Yes/YES/Y/y; when Yes on a Self row demo_email_expiry_date is required (AV:545-550). |
| 50 | `Demo Email Expiry Date` | `demo_email_expiry_date` | cond. | is_demo_email = Yes and relationship = Self | validDate; >= DOJ and >= today (AV:520-543). Header slug maps to demo_email_expiry_date (IMP:367-372). |
| 51 | `Should Verify Email` | `should_verify_email` | - |  | Yes/No; job treats No/NO/N/n as "do not verify" for Self (OptimizedMemberEndorsementJob.php:693,920). No validation. |
| 52 | `Default Password` | `default_password` | - |  | Optional; min length 8 (10 on TATA); must contain lowercase, uppercase, digit and one of !@#$%^&*; TATA: must not contain first name (AV:2860-2877, 153). |
| 53 | `Is Dummy Email` | `is_dummy_email` | - |  | Yes/No dropdown; no validation located. |
| 54 | `To Hide Email` | `to_hide_email` | - |  | Yes/No dropdown; no validation located. |
| 55 | `Is Dummy Mobile` | `is_dummy_mobile` | - |  | Yes/No dropdown; no validation located. |
| 56 | `To Hide Mobile` | `to_hide_mobile` | - |  | Yes/No dropdown; no validation located. |
| 57 | `Is Password Reset` | `is_password_reset` | - |  | If present must be one of Yes, YES, Y, y, No, NO, N, n (AV:193-199). |
| 58 | `Employee Annual Salary 2` | `employee_annual_salary_2` | cond. | Self row AND NUMBER_OF_TIME_SALARY AND calculate_eligibility_from = salary_2 | AV:1072-1076; numeric (AV:2882). |
| 59 | `Employee Variables in Salary 2` | `employee_variables_in_salary_2` | - |  | Optional numeric (AV:2891). |
| 60 | `Employee Annual Salary 3` | `employee_annual_salary_3` | cond. | Self row AND NUMBER_OF_TIME_SALARY AND calculate_eligibility_from = salary_3 | AV:1078-1082; numeric (AV:2885). |
| 61 | `Employee Variables in Salary 3` | `employee_variables_in_salary_3` | - |  | Optional numeric (AV:2894). |
| 62 | `Flex Plan` | `flex_plan` | - |  | Dropdown of policy flex plans (only when no broker custom sheet; SFC:384-389). Job looks up PolicyFlexPlans.plan_name (OptimizedMemberEndorsementJob.php:1144-1146); no validator error located. |
| 63 | `Self SI Selection` | `self_si_selection` | - |  | Dropdown No/Yes; no validation located (UNKNOWN). |
| 64 | `Cover Type` | `cover_type` | cond. | IPD suminsured type = INDIVIDUAL_AND_FAMILY_COVER (3) | Allowed exactly "Individual" or "Family Cover" (AV:1000-1008 Self, 1298-1307 dependants). Both parents (and both parents-in-law) must use the same cover type (AV:1309-1357). |
| 65 | `Other 1` | `other_1` | - |  | Free text stored in members.others JSON (other_1..other_10); no validation. |
| 66 | `Other 2` | `other_2` | - |  | Free text stored in members.others JSON; no validation. |
| 67 | `Other 3` | `other_3` | - |  | Free text stored in members.others JSON; no validation. |
| 68 | `Other 4` | `other_4` | - |  | Free text stored in members.others JSON; no validation. |
| 69 | `Other 5` | `other_5` | - |  | Free text stored in members.others JSON; no validation. |
| 70 | `Member Existing Claim` | `member_existing_claim` | - |  | Dropdown NO/YES; no validation located (UNKNOWN). |
| 71 | `Data Received On` | `data_received_on` | - |  | Optional validDate (AV:552-559). |
| 72 | `Other 6` | `other_6` | - |  | Free text stored in members.others JSON; no validation. |
| 73 | `Insurer Endorsement Id` | `insurer_endorsement_id` | - |  | Optional; stored (insurer bookkeeping). Slug copy at IMP:340-342. No validation. |
| 74 | `Other 7` | `other_7` | - |  | Free text stored in members.others JSON; no validation. |
| 75 | `Insurer Endorsement Date` | `insurer_endorsement_date` | - |  | Optional; parsed validDate -> Y-m-d (IMP:361-366). |
| 76 | `Other 8` | `other_8` | - |  | Free text stored in members.others JSON; no validation. |
| 77 | `Insurer Removal Id` | `insurer_removal_id` | - |  | Optional stored value (IMP:350-354). |
| 78 | `Other 9` | `other_9` | - |  | Free text stored in members.others JSON; no validation. |
| 79 | `Insurer Correction Id` | `insurer_correction_id` | - |  | Optional stored value (IMP:344). |
| 80 | `Other 10` | `other_10` | - |  | Free text stored in members.others JSON; no validation. |
| 81 | `Insurer Correction Date` | `insurer_correction_date` | - |  | Optional; validDate -> Y-m-d (IMP:346-351). |
| 82 | `Insurer Removal Date` | `insurer_removal_date` | - |  | Optional; validDate -> Y-m-d (IMP:355-360). |
| 83 | `Installment` | `installment` | - |  | Optional; first space-separated token must be digits, e.g. "3 Months" (AV:588-594). Dropdown "1 Month"/"N Months" from PolicyPayrollInstallment (SFC:290-301). |
| 84 | `Has Death Certificate` | `has_death_certificate` | - |  | Yes/Y/y -> 1, anything else -> 0 (IMP:436-445). Parent/parent-in-law rows: rules in validateDeathCertificate (AV:3140-3390) depend on policy_age_details.is_death_certificate_required; at most one of both parents may be flagged (AV:842-861). |
| 85 | `Batch Id` | `batch_id` | - |  | Regex /^\w+$/ ("Batch ID can only be alphanumeric", AV:260-267). |
| 86 | `Insurer Client Id` | `insurer_client_id` | - |  | /^\w+$/; must not already exist >1 times in policy members (AV:2500-2530, "client id" messages AV:99-103). |
| 87 | `Tpa Member Id` | `tpa_member_id` | - |  | Informational in ADD (stored in dump); no validation located. |
| 88 | `Tpa Member Name` | `tpa_member_name` | - |  | Informational in ADD; no validation located. |
| 89 | `Ecard Url` | `ecard_url` | - |  | Informational in ADD; no validation located. |
| 90 | `Serial Number` | `serial_number` | - |  | No validation located (UNKNOWN). |
| 91 | `Occupation` | `occupation` | - |  | No validation located (UNKNOWN). |
| 92 | `Aadhar Number` | `aadhar_number` | - |  | If present: /^[2-9]\d{11}$/, exactly 12 chars, no spaces (AV:752-767). |
| 93 | `ABHA ID` | `abha_id` | - |  | No validation located (UNKNOWN). |
| 94 | `Account Number` | `account_number` | - |  | No validation located (UNKNOWN). |
| 95 | `VPN ID` | `vpn_id` | - |  | Dropdown = MasterVpn.name (SFC:339-342); no validator error located. |
| 96 | `Nominee Name` | `nominee_name` | - |  | Optional. Nominee captured only for Self rows (job: nominee_name + relationship_with_insured both non-null). |
| 97 | `Nominee DOB` | `nominee_dob` | - |  | validDate; if nominee age < 18 -> appointee_name AND relation_of_appointee_with_nominee mandatory, and an adult relation (Self/Spouse/Partner/parents) with age<18 is rejected (AV:696-726). |
| 98 | `Nominee Email` | `nominee_email` | - |  | Optional; no spaces + isEmailValid (AV:728-742). |
| 99 | `Nominee Mobile Number` | `nominee_mobile_number` | - |  | Optional; 10 digits numeric (AV:744-750). |
| 100 | `Appointee Name` | `appointee_name` | cond. | nominee_dob implies nominee age < 18 | AV:721-725. |
| 101 | `Relationship With Insured` | `relationship_with_insured` | - |  | Nominee relation: a RelationMaster.name (used for adult check AV:709-720). Dropdown = active RelationMaster names (SFC:334-338). |
| 102 | `Relation Of Appointee With Nominee` | `relation_of_appointee_with_nominee` | cond. | nominee age < 18 | AV:719-721. Dropdown = active RelationMaster names. |
| 103 | `Nominee Contribution` | `nominee_contribution` | - |  | Self rows only; numeric and <= 100 (AV:769-778). |

### 2.2 Additional / conditional columns

| Label | Field | Present when |
|---|---|---|
| `Employer Name` | `employer_name` | only if the policy/employer has child entities, otherwise removed (SampleFileController.php:497-515 default path) |
| `Family Number` | `family_number` | portal EDME_ALL\|EMPLOYEE_BENEFIT only (seeded inside Portal::check, AdditionEndorsementColumnMappingSeeder.php:963 / CorrectionEndorsementColumnMappingSeeder.php:786) |
| `License Start Date` | `license_start_date` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `License Expiry Date` | `license_expiry_date` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `Type of license` | `type_of_license` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `Issuing authority` | `issuing_authority` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `License Issuance date` | `license_issuance_date` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `Cover Effective Date` | `cover_effective_date` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `License Number` | `license_number` | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (LolEndrosementAdditionSeeder.php:24-28 else soft-deleted) |
| `Copayment` | `copayment` | only if policy rates contain a copayment rate (is_copayment_rate=1), otherwise removed (SampleFileController.php:525-545) |
| `Copayment Percentage` | `copayment_percentage` | only if policy rates contain a copayment rate (is_copayment_rate=1), otherwise removed (SampleFileController.php:525-545) |

Inserted at its `sequence` position (`Employer Name` seq 30, `Copayment` 133, `Copayment Percentage` 134, `Family Number` 33, LOL columns 125-130).

### 2.3 Variant overlays (what changes by policy shape)

Header sets are identical across variants; the overlays list columns whose *requirement* changes. Full machine form: JSON `ADD.<variant>`.

| Variant | Applies when | Column -> requirement |
|---|---|---|
| ipd_flat_suminsured | policy rater IPD/IPD+OPD/BASE_PLUS_TOP_UP/BASE_ENHANCE_FLEX (PolicyDTO.php:41-46) and ipd suminsured sub-type FLAT(1) | `suminsured`: REQUIRED (every row if suminsured type INDIVIDUAL; Self row only if FAMILY_COVER) |
| ipd_no_rule | ipd suminsured sub-type NO_RULE(16) | `suminsured`: REQUIRED (every row if INDIVIDUAL; Self row if FAMILY_COVER) |
| opd_flat_suminsured | rater OPD(2) or IPD+OPD(3) with opd sub-type FLAT or NO_RULE | `opd_suminsured`: REQUIRED (same Self/Individual rule as IPD) |
| grade_rater | ipd/opd suminsured sub-type in [3,13,12,18,26,23] or premium type in [4,6,14,15,24,29] (constant.php:55,159) | `employee_grade`: REQUIRED (Self rows) |
| designation_rater | ipd/opd suminsured sub-type in [4,14] or premium type in [3,7,16] | `employee_designation`: REQUIRED (Self rows) |
| number_of_time_salary_rater | ipd or opd suminsured sub-type NUMBER_OF_TIME_SALARY(5) | `employee_annual_salary`: REQUIRED (calculate_eligibility_from = salary_1); `employee_annual_salary_2`: REQUIRED (calculate_eligibility_from = salary_2); `employee_annual_salary_3`: REQUIRED (calculate_eligibility_from = salary_3); `employee_number_of_time_salary`: REQUIRED (Self rows) |
| relation_wise_number_of_time_salary | sub-type 15 or premium type 17 | `insured_member_number_of_time_salary`: REQUIRED (non-Self rows) |
| age_premium_rater | premium type MEMBER_AGE(8) / EMPLOYEE_AGE(2) (or suminsured sub-type AGE(2)) | `insured_member_dob`: REQUIRED (non-Self rows (MEMBER_AGE)); `employee_dob`: REQUIRED (Self rows (always)) |
| individual_and_family_cover | ipd suminsured type INDIVIDUAL_AND_FAMILY_COVER(3) | `cover_type`: REQUIRED (every row) |
| copayment_policy | policy rates contain is_copayment_rate=1 | `copayment`: optional; `copayment_percentage`: REQUIRED (copayment = Yes) |
| gral_premium | ipd/opd premium type GRAL(31) | `work_location`: REQUIRED (Self rows) |
| lol_policy | policy_sub_type_id LOL_BASE(7) / LOL_TOP_UP(8) | `license_start_date`: optional; `license_expiry_date`: optional; `type_of_license`: optional; `issuing_authority`: optional; `license_issuance_date`: optional; `cover_effective_date`: optional; `license_number`: optional; `employee_grade`: REQUIRED (LOL grade raters (sub-type 23/26)); `suminsured`: optional |
| parent_death_certificate | relation is Father/Mother/Father-in-law/Mother-in-law and policy_age_details.is_death_certificate_required=1 | `has_death_certificate`: optional (such parent rows) |
| child_entities | employer has child companies | `employer_name`: optional |
| tpa_policy | policy has a TPA | `tpa_member_id`: optional |

Policy *type* (Group vs Top-up) and *TPA* do not change the ADD header set or its mandatory cells in the validators: only `suminsured` source options (top-up raters `BASE_WISE_SI_AND_PREMIUM`, `BASED_ON_BASE_SI_AND_TOPUP_PREMIUM` list `top_up_suminsured`, `SFC:173-211`) and, for the TPA, three informational columns (`Tpa Member Id`, `Tpa Member Name`, `Ecard Url`, seq 131-132) are present for every policy. TPA-specific member-id upload is a separate format (id 4 "Offline TPA Member", JSON `OTHER_FORMATS_REFERENCE`).

### 2.4 Row-level rules worth re-implementing

* Per-employee limits are checked **across the sheet and the DB together**: max members (`no_of_member`), max children (`max_child`, twins allowed via `max_twins` when same DOB), max parents (`max_parents`), spouse/partner/sibling counts from `policy_age_details.no_of_relation`, parent cross-selection (`has_parent_cross_selection=0` forbids mixing parents and in-laws), `restrict_same_gender_parents`, inclusive child/sibling and spouse/partner flags (AV:1360-1560, 1670-1760, 2012-2500). Excess rows get "Number of <relation> allowed into the policy limit reached." / "Max member allowed in policy limit reached."
* Existing-employee ADD (dependant added later): the Self need not be in the sheet; employee dob/gender/etc. are read from the DB (AV:1560-1620).
* Employee Coverage lock-in: relation flagged `is_mandatory = No` in `EmployeeCoverageDetail` is refused (AV:1608-1618).
* Death-certificate logic for parents: only checked when `policy_age_details.is_death_certificate_required=1`; at most one of the two parents (and in-laws) may carry `has_death_certificate=Yes` per employee (AV:842-861, 3140-3390).
* Nominee columns (TAGIC fields): Self rows only; `Nominee Contribution` <= 100; minor nominee (<18) forces `Appointee Name` + `Relation Of Appointee With Nominee`; Aadhar `^[2-9][0-9]{11}$` (AV:696-778).

## 3. REMOVE sheet (format id 2, "Member Removal")

Sources: `DeletionEndorsementColumnMappingSeeder.php:20-140` (13 rows), `DefaultEndrosementFormatFeildsMappingSeeder.php:31278-31524` (batch id, inception date, reason of removal, others 1-3), `DefaultEndorsementSheetEndorsementFields.php:21` (member existing claim), `DefaultEndorsementInsurerEndorsementFields.php` (insurer ids/dates; correction id/date are seeded soft-deleted), `MiscEndorsementFieldsSeeder.php:34` (insurer client id).

**How a member is identified (RV:138-266).** Either (a) `Endrosement Number` (the member's `endorsement_id`), or (b) `Employee Code` + `Relationship with Employee` (+ optional `Employee DOB`, `Insured Member First Name`, `Insured Member DOB`, `Tpa Member Id` to disambiguate dependants). Path (b) resolves exactly one member or errors ("Multiple members found with same data" / "Member not found." / "Employee not found."), then writes the id back into `endrosement_number`. The seed flags `Endrosement Number` and `Employee Leaving Date` as mandatory; the code makes only `Employee Leaving Date` unconditional. Already-deleted members -> "Member is already inactive." (RV:250-253). `Employee Leaving Date` has no range check against policy dates in the validator. Removing Self also stamps `reason_of_removal`, `others_*`, insurer removal id/date on every dependant row (OptimizedRemoveJob.php:258-270).

### 3.1 Default header list (resolved) - 23 columns

| # | Header label (exact) | Field | Mand. | Condition / seed flag | Value format & validation (file:line) |
|---|---|---|---|---|---|
| 1 | `Employee Code` | `employee_code` | cond. | endrosement_number is blank | RV:140-143 required when no endrosement_number. Looked up in MasterEmployee.code for the policy employer (+child entities) (RV:158-205). |
| 2 | `Endrosement Number` | `endrosement_number` | cond. | employee_code + relationship_with_employee not both supplied | Primary member key = employee_members.endorsement_id (8 random letters, helper.php:965-969). If supplied it must exist, belong to this policy, and not be already deleted ("Member is already inactive.") (RV:250-266). If blank, member is resolved from employee_code + relationship (+ optional dob/first name) and the id is filled in (RV:158-240). Seed flag is_mandatory=1 but the code makes it conditional. Header spelling is "Endrosement Number" (typo is load-bearing: slug endrosement_number, IMP:115,174). |
| 3 | `Employer Name` | `employer_name` | - |  | Optional; if supplied must be an existing entity name else "Invalid Employer Name" (RV:97-104); also scopes the employee lookup to that entity. |
| 4 | `Employee First Name` | `employee_first_name` | - |  | Optional; not used by the matcher (only echoed in HRMS file). |
| 5 | `Member Existing Claim` | `member_existing_claim` | - |  | Dropdown NO/YES; no validation located (UNKNOWN). Seeded by DefaultEndorsementSheetEndorsementFields.php:21. |
| 6 | `Employee Last Name` | `employee_last_name` | - |  | Optional; not used by the matcher. |
| 7 | `Relationship with Employee` | `relationship_with_employee` | cond. | endrosement_number is blank | RV:144-153: required then, and must be a RelationMaster.name ("Invalid Relationship."). Importer maps label to relation_id (IMP:528-592). |
| 8 | `Employee DOB` | `employee_dob` | - |  | Optional disambiguator when matching by employee_code; validDate else "Employee dob format is invalid." (RV:160-170). When given the employee must have that dob. |
| 9 | `Employee Leaving Date` | `employee_leaving_date` | YES |  | ALWAYS required (RV:86-95: "Employee date of leaving is required."). validDate formats (d/m/Y, d-m-Y, Y-m-d, Y/m/d, d.m.Y, Excel serial); invalid -> "Invalid employee leaving date." No upper/lower bound vs policy dates in this validator. Stored as leaving_date on member and employee (OptimizedRemoveJob.php:366,387-427). |
| 10 | `Insured Member DOB` | `insured_member_dob` | - |  | Optional disambiguator for non-Self rows; invalid -> "Member dob format is invalid." (RV:186-197). With first name narrows to one member; >1 match -> "Multiple members found with same data", 0 -> "Member not found." (RV:226-236). |
| 11 | `Insured Member First Name` | `insured_member_first_name` | - |  | Optional disambiguator for non-Self rows (RV:199-205). |
| 12 | `Tpa Member Id` | `tpa_member_id` | - |  | Optional. If supplied must exist as employee_members.tpa_member_id or insurer_member_id else "TPA Member ID Not Found" (RV:269-282). On AIG portals it also narrows dependants (tpa_member_id for TAGIC TPA else insurer_member_id) (RV:207-224). |
| 13 | `Effective Date` | `effective_date` | - |  | Optional validDate; must be <= employee_leaving_date ("Effective date should be precede of date of leaving.") (RV:107-120). Saved as deletion_effective_date (OptimizedRemoveJob.php:302-303). |
| 14 | `Has Death Certificate` | `has_death_certificate` | - |  | Yes/Y/y -> 1 else 0 (IMP:436-445). For Father/Mother/Father-in-law/Mother-in-law rows the flag must agree with uploaded EmployeeMemberDeathCertificate when policy_age_details.is_death_certificate_required=1 ("Death Certificate is not uploaded for this member" / "Death Certificate Found, has_death_certificate column should be YES") (RV:302-452). |
| 15 | `Insurer Removal Id` | `insurer_removal_id` | - |  | Optional stored value; written to member.insurer_removal_id (OptimizedRemoveJob.php:~285). |
| 16 | `Insurer Removal Date` | `insurer_removal_date` | - |  | Optional; validDate -> Y-m-d (IMP:355-360). |
| 17 | `Inception/Endorsement Date` | `inceptionendorsement_date` | - |  | Optional validDate else "Invalid inception/endorsement date" (RV:122-127). If absent the importer fills today (IMP:334-336). Saved as member.endorsement_date. |
| 18 | `Batch ID` | `batch_id` | - |  | Regex /^\w+$/ (job validateData same rule); saved as batch_id_remove. |
| 19 | `Insurer Client Id` | `insurer_client_id` | - |  | /^\w+$/, unique in sheet and in policy members (RV:487-512). |
| 20 | `Reason of Removal` | `reason_of_removal` | cond. | policy has a lock-in configuration that applies to the relation (OptimizedRemoveJob.php:677-697 only; not in EndorsementRemovalValidation) | Free text. Saved to member.reason_of_removal; propagated to dependants when Self is removed (OptimizedRemoveJob.php:258-270, 295). |
| 21 | `Others 1` | `others_1` | - |  | Free text saved as others_1 (header "Others 1"). |
| 22 | `Others 2` | `others_2` | - |  | Free text saved as others_2. |
| 23 | `Others 3` | `others_3` | - |  | Free text saved as others_3. |

Not on the default sheet: `Insurer Correction Id`, `Insurer Correction Date` (seeded soft-deleted: `DefaultEndorsementInsurerEndorsementFields.php:128,206`).

### 3.2 Variant overlays

| Variant | Applies when | Column -> requirement |
|---|---|---|
| by_endorsement_number | endrosement_number filled | `endrosement_number`: REQUIRED - Sufficient with employee_leaving_date.; `employee_leaving_date`: REQUIRED - Always required. |
| by_code_and_relationship | endrosement_number blank | `employee_code`: REQUIRED; `relationship_with_employee`: REQUIRED; `employee_leaving_date`: REQUIRED; `employee_dob`: optional - optional narrowing; `insured_member_first_name`: optional - optional narrowing for dependants; >1 match errors; `insured_member_dob`: optional - optional narrowing |
| lockin_policy | policy has lock-in configuration | `reason_of_removal`: REQUIRED (relation falls under the lock-in) - OptimizedRemoveJob.php:677-697: "Cannot remove Locked in member without reason for removal". Present in job validateData only (non-EB/UAT/PROD portals) - the EndorsementRemovalValidation class used on EB/UAT/PROD has no such check. |
| parents_death_certificate | removing parents/parents-in-law where is_death_certificate_required=1 | `has_death_certificate`: REQUIRED (such rows) - RV:302-452 |
| aig_tpa | AIG portals | `tpa_member_id`: optional - RV:207-224: disambiguates dependants; for TAGIC TPA (getTataAigTpaId) compared to tpa_member_id else insurer_member_id. |
| self_excluded_policy | policy age details have no Self relation | `relationship_with_employee`: optional - RV:284-300: cannot remove the only Self/Spouse while children remain ("You cannot remove this member, as child is dependent."). |

Validator selection: `OptimizedRemoveJob` uses `EndorsementRemovalValidation` on portals EMPLOYEE_BENEFIT/UAT/PROD, otherwise its own `validateData()` (`OptimizedRemoveJob.php:132-146, 477-930`); the two differ (lock-in/reason check exists only in `validateData`).

## 4. UPDATE / CORRECTION sheet (format id 3, "Member Correction")

Sources: `CorrectionEndorsementColumnMappingSeeder.php:21-801` (66 rows), `DefaultEndrosementFormatFeildsMappingSeeder.php:33461-33476` (demo email), `LolEndrosementAdditionSeeder.php:109-546`, `DefaultEndorsementInsurerEndorsementFields.php` (insurer ids), `Tata two-column` rows excluded.

**Identification (CV:196-345).** `Endrosement Number` (preferred; must belong to this policy and, if `Relationship with Employee` is also given, match that member's relation) **or** `Existing Employee Code` + `Relationship with Employee` (+ optional `Existing Employee DOB`, `Existing Insured Member First Name`, `Existing Insured Member DOB`). The `Employee ...`/`Insured Member ...` columns carry the NEW values. `Type Of Correction` is required on every row (CV:577-579).

**Semantics: partial update.** Every non-blank cell on a validated row is applied; blank cells leave the stored value untouched (`OptimizedUpdateEndorsementJob.php:220-830`). The relationship of a member cannot be changed (the label must equal the stored relation, CV:350-353). Changing `Employee Code` of a Self member fails if the new code exists (CV:614-631); `Employee Email` fails if the email belongs to any user (CV:636-661). `update_data_in_all_policies=1` (upload flag) copies the personal data to the member's other policies (`updateMemberDataInAllPolicies`).

### 4.1 `Type Of Correction` values

Dropdown (only on portals EMPLOYEE_BENEFIT/AIG_ALL; free text elsewhere, SFC:343-349): `Insured member details correction`; `SI Correction`; `DOB Correction`; `Employee Designation/Grade Correction`; `Nominee Details Correction`; `Appointee Details Correction`.

| Value | Enforced behaviour in code | Columns it may change |
|---|---|---|
| any non-blank text | stored as `employee_members.type_of_correction`, `correction_date=now` (UpdateJob:752-760) | **No per-type column restriction exists.** Any filled column is applied. |
| `Nominee Details Correction` | extra validation: `Relationship With Insured` required and an existing nominee with that relation must exist in the policy; nominee <18 needs appointee name + appointee relation (CV:903-935). Job adds/updates the nominee only if `Nominee Name` AND `Relationship With Insured` are present on a Self row (UpdateJob:832-834). | nominee_name, nominee_dob, nominee_email, nominee_mobile_number, appointee_name, relationship_with_insured, relation_of_appointee_with_nominee, nominee_contribution is not in format 3 |
| `Insured member details correction`, `DOB Correction` | for members with a `tpa_member_id` on AIG portals the TAGIC e-card is regenerated (UpdateJob:1236-1240) | intended: member name/gender/email/mobile/address; employee/member DOB (inference from the label) |
| `SI Correction` | none beyond SI validation (CV:970-1050); `GcSummaryDataService` treats it as NON-NIL endorsement (premium impact) (`GcSummaryDataService.php:1357-1363`) | suminsured, opd_suminsured, employee_no_of_time_suminsured (intended) |
| `Employee Designation/Grade Correction` | classed "dicey" (premium may change) in GcSummary; no code gate | employee_designation, employee_grade (intended) |
| `Appointee Details Correction` | classed NIL; no code gate | appointee_name, relation_of_appointee_with_nominee (intended) |

"Intended" columns are inferred from the label only; code does not enforce them (UNKNOWN whether the UI restricts them). `GcSummaryDataService.php:1455,1458` only special-cases `DOB Correction` and `Employee Designation/Grade Correction`.

### 4.2 Default header list (resolved) - 82 columns

| # | Header label (exact) | Field | Mand. | Condition / seed flag | Value format & validation (file:line) |
|---|---|---|---|---|---|
| 1 | `Endrosement Number` | `endrosement_number` | cond. | existing_employee_code + relationship_with_employee not supplied | Primary key = employee_members.endorsement_id. If blank, requires existing_employee_code + relationship_with_employee and the member is resolved (CV:196-345). Must belong to this policy ("Invalid endrosement number for this policy.", CV:345-348) and match relationship if given (:350-353). Seed flag is_mandatory=1 but code makes it conditional. |
| 2 | `Type Of Correction` | `type_of_correction` | YES |  | ALWAYS required, any non-null text (CV:577-579). Dropdown (only on EB/AIG portals; other portals free text): "Insured member details correction", "SI Correction", "DOB Correction", "Employee Designation/Grade Correction", "Nominee Details Correction", "Appointee Details Correction" (SFC:343-349). Stored on member as-is (OptimizedUpdateEndorsementJob.php:752-753). Only the exact string "Nominee Details Correction" triggers extra validation (CV:903-935); "Insured member details correction"/"DOB Correction" regenerate TAGIC e-cards for members with tpa_member_id (UpdateJob:1236-1240). No other per-type column gating exists: ANY non-blank cell in a row is applied regardless of the type label (UpdateJob:477-830). GcSummary classes types as nil (Nominee/Appointee/Insured member details) / non-nil (SI Correction) / dicey (DOB, Designation/Grade) for premium impact (GcSummaryDataService.php:1357-1363). |
| 3 | `Effective Date` | `effective_date` | - |  | validDate; within [policy start, policy end]; >= DOJ when DOJ provided (CV:518-538). Saved as correction_effective_date. |
| 4 | `Is Password Reset` | `is_password_reset` | - |  | Yes/YES/Y/y/No/NO/N/n (CV:183-188). |
| 5 | `Employee Code` | `employee_code` | - |  | NEW employee code (rename). Regex as add; for a Self member the new code must not already exist in the employer ("Employee code already taken.", CV:614-631); for non-Self it must exist. |
| 6 | `Existing Employee Code` | `existing_employee_code` | cond. | endrosement_number blank | Lookup key (current code) (CV:198-203, 250-305). |
| 7 | `Employee First Name` | `employee_first_name` | - |  | Name regex (CV:679-683). If sheet has only the employee_* name and member is non-Self it is moved to insured_member_* (IMP:188-200) and employee_* cleared for type 5. |
| 8 | `Employee Last Name` | `employee_last_name` | - |  | Name regex (CV:684-688). |
| 9 | `Employee Gender` | `employee_gender` | - |  | male/Male/female/Female/Other/other (CV:719-724). |
| 10 | `Employee DOB` | `employee_dob` | - |  | validDate; for Self, must be <= marriage_date and DOM-DOB >= 18y when member has marriage_date (CV:432-451); Self dob must equal insured_member_dob if both given. |
| 11 | `Existing Employee DOB` | `existing_employee_dob` | - |  | Optional lookup narrowing; validDate else "Employee dob format is invalid." (CV:250-270). |
| 12 | `Employee Date of Joining` | `employee_date_of_joining` | - |  | validDate (CV:509-514). |
| 13 | `Insurer Endorsement Id` | `insurer_endorsement_id` | - |  | Stored only if member has none yet (UpdateJob:236-240). |
| 14 | `Insurer Endorsement Date` | `insurer_endorsement_date` | - |  | Stored only if member has none yet (UpdateJob:242-245). |
| 15 | `Employee Email` | `employee_email` | - |  | Must NOT already belong to any user ("Email ID already taken."), pattern + no space + isEmailValid (CV:636-661). |
| 16 | `Insurer Removal Id` | `insurer_removal_id` | - |  | Parsed by importer; not applied by the correction job (UNKNOWN). |
| 17 | `Employee Alternate Email` | `employee_alternate_email` | - |  | Pattern + no space + isEmailValid (CV:663-677). |
| 18 | `Employee Mobile Number` | `employee_mobile_number` | - |  | 10 digits numeric (CV:740-745). |
| 19 | `Insurer Correction Id` | `insurer_correction_id` | - |  | Stored (UpdateJob:220-227). |
| 20 | `Mobile No Code` | `mobile_no_code` | - |  | Must exist in master_countries.mobile_code (CV:176-181). |
| 21 | `Employee Alternate Mobile Number` | `employee_alternate_mobile_number` | - |  | 10 digits, numeric, differs from employee mobile and from insured member mobile (CV:746-786). |
| 22 | `Employee Designation` | `employee_designation` | - |  | Optional; applied if non-blank. |
| 23 | `Insurer Correction Date` | `insurer_correction_date` | - |  | Stored (UpdateJob:228-234). |
| 24 | `Employee Grade` | `employee_grade` | - |  | Optional; applied if non-blank. LOL grade raters: SI-in-grade check (CV:359-405). |
| 25 | `Insurer Removal Date` | `insurer_removal_date` | - |  | Parsed by importer; not applied by the correction job (UNKNOWN). |
| 26 | `Employee Annual Salary` | `employee_annual_salary` | - |  | Optional; applied if non-blank (no numeric validator in correction validator; UNKNOWN). |
| 27 | `Employee Variables in Salary` | `employee_variables_in_salary` | - |  | Optional; applied if non-blank. |
| 28 | `Employee Number of Time Salary` | `employee_number_of_time_salary` | - |  | Optional; copied to member.number_of_time_salary when type_of_correction present (UpdateJob:754-756). |
| 29 | `Employee Address` | `employee_address` | - |  | Optional. |
| 30 | `Batch Id` | `batch_id` | - |  | Optional; applied (UpdateJob:809-812). |
| 31 | `Employee State` | `employee_state` | - |  | Must equal master_state.state_name (CV:1062-1067). |
| 32 | `Employee City` | `employee_city` | - |  | Must equal master_city.city_name (CV:1069-1074). |
| 33 | `Employee Pin Code` | `employee_pin_code` | - |  | Exactly 6 chars and digits (CV:1076-1082). |
| 34 | `Suminsured` | `suminsured` | - |  | Optional. Validated against policy rates: grade raters need grade_suminsured for that grade; designation raters des_suminsured; FLAT needs flat_suminsured; NUMBER_OF_TIME_SUMINSURED needs flat_suminsured with is_opd_rate=0 AND employee_no_of_time_suminsured. Member must already have a suminsured ("Unable to correct suminsured as no suminsured assigned against this member") (CV:970-1050). |
| 35 | `Employee Premium` | `employee_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 36 | `Employer Premium` | `employer_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 37 | `OPD Suminsured` | `opd_suminsured` | - |  | Optional; opd flat rate must exist when opd sub-type = 1 (CV:1012-1030). |
| 38 | `OPD Employee Premium` | `opd_employee_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 39 | `OPD Employer Premium` | `opd_employer_premium` | - |  | No validation or consumption located (UNKNOWN). |
| 40 | `Installment` | `installment` | - |  | First token digits (CV:569-575). |
| 41 | `Relationship with Employee` | `relationship_with_employee` | - |  | Optional; if supplied must be a relation of the policy and equal the member's relation (CV:580-590, 350-353). It cannot be used to change the relation. |
| 42 | `Date of Marriage` | `date_of_marriage` | - |  | validDate (CV:500-507). For Self: DOM must be >= 18y after Self DOB and Spouse DOB, then spouse marriage_date is overwritten (CV:1287-1335). For Spouse: must equal Self DOM (CV:1287-1300). |
| 43 | `Employee Unit` | `employee_unit` | - |  | Must be in employer-mapped units (CV:~832-842). |
| 44 | `Insured Member First Name` | `insured_member_first_name` | - |  | Name regex (CV:689-694). Son/Daughter: rejected if another child of same relation with same first name already exists ("Child with same name already exist into same policy.", CV:224-250, 306-330). |
| 45 | `Existing Insured Member First Name` | `existing_insured_member_first_name` | - |  | Optional lookup narrowing for non-Self (CV:272-300). |
| 46 | `Insured Member Last Name` | `insured_member_last_name` | - |  | Name regex (CV:695-700). |
| 47 | `Insured Member Gender` | `insured_member_gender` | - |  | Relation/gender consistency checked against the CURRENT member relation (CV:727-737). |
| 48 | `Insured Member DOB` | `insured_member_dob` | - |  | validDate; Spouse: <= marriage_date and DOM-DOB >= 18y; child min_age_months via calculateCoverStartdate; cannot update DOB of a special child ("Can not update special child DOB.") (CV:461-498). Age rechecked vs policy min/max (CV:1108-1180). |
| 49 | `Insured Member Email` | `insured_member_email` | - |  | No space + isEmailValid (CV:697-709). |
| 50 | `Existing Insured Member DOB` | `existing_insured_member_dob` | - |  | Optional lookup narrowing; validDate (CV:208-221, 272-282). |
| 51 | `CTC` | `ctc` | - |  | Optional; applied (UpdateJob:622). |
| 52 | `Location` | `location` | - |  | Optional; applied (UpdateJob:625). |
| 53 | `Zone` | `zone` | - |  | Optional; applied (UpdateJob:628). |
| 54 | `Employee No of Time Suminsured` | `employee_no_of_time_suminsured` | cond. | suminsured policy sub-type NUMBER_OF_TIME_SUMINSURED (28) and suminsured/opd_suminsured supplied | CV:1034-1050. |
| 55 | `Is Dummy Email` | `is_dummy_email` | - |  | Yes/No; applied via user flags (UpdateJob:428-435). |
| 56 | `To Hide Email` | `to_hide_email` | - |  | Yes/No (UpdateJob:452). |
| 57 | `Is VIP Employee` | `is_vip_employee` | - |  | Yes/No (UpdateJob:586-588). |
| 58 | `Insured Member Mobile Number` | `insured_member_mobile_number` | - |  | 10 digits numeric (CV:788-794). |
| 59 | `Is Dummy Mobile` | `is_dummy_mobile` | - |  | Yes/No (UpdateJob:460). |
| 60 | `To Hide Mobile` | `to_hide_mobile` | - |  | Yes/No (UpdateJob:468). |
| 61 | `Insured Member Address` | `insured_member_address` | - |  | Optional. |
| 62 | `Insured Member City` | `insured_member_city` | - |  | master_city.city_name (CV:1090-1096). |
| 63 | `Insured Member State` | `insured_member_state` | - |  | master_state.state_name (CV:1083-1089). |
| 64 | `Insured Member Pin Code` | `insured_member_pin_code` | - |  | 6 digits (CV:1097-1103). |
| 65 | `Employee Annual Salary 2` | `employee_annual_salary_2` | - |  | Optional; applied to employee.salary_1 (UpdateJob:537). |
| 66 | `Employee Variables in Salary 2` | `employee_variables_in_salary_2` | - |  | Optional (UpdateJob:543). |
| 67 | `Employee Annual Salary 3` | `employee_annual_salary_3` | - |  | Optional; applied to employee.salary_2 (UpdateJob:539). |
| 68 | `Employee Variables in Salary 3` | `employee_variables_in_salary_3` | - |  | Optional (UpdateJob:545). |
| 69 | `Member Existing Claim` | `member_existing_claim` | - |  | Optional; applied (UpdateJob:790-802). |
| 70 | `Default Password` | `default_password` | - |  | Self member only ("Password can be set for employees only"); min 8 (10 TATA) + pattern (CV:409-430). |
| 71 | `Is Demo Email` | `is_demo_email` | - |  | Yes -> expiry required for Self (CV:560-566). |
| 72 | `Demo Email Expiry Date` | `demo_email_expiry_date` | - |  | validDate; >= DOJ (if given) and >= today (CV:540-558). |
| 73 | `Aadhar Number` | `aadhar_number` | - |  | /^[2-9]\d{11}$/ 12 digits, no spaces (CV:947-960). |
| 74 | `Nominee Name` | `nominee_name` | - |  | Applied for Self rows only when nominee_name AND relationship_with_insured both non-null (UpdateJob:832-834 addOrUpdateNominee). |
| 75 | `Nominee DOB` | `nominee_dob` | - |  | validDate (CV:880-888); when type_of_correction = "Nominee Details Correction" and age < 18 appointee relation + name required (CV:903-935). |
| 76 | `Appointee Name` | `appointee_name` | cond. | type_of_correction = Nominee Details Correction and nominee age < 18 | CV:925-932. |
| 77 | `Relationship With Insured` | `relationship_with_insured` | cond. | type_of_correction = Nominee Details Correction | Required then; an EmployeeNominee with that relation must exist in the policy ("No nominee found with the given relationship in the policy") (CV:903-917). |
| 78 | `Relation Of Appointee With Nominee` | `relation_of_appointee_with_nominee` | cond. | Nominee Details Correction and nominee age < 18 | CV:925-932. |
| 79 | `Nominee Email` | `nominee_email` | - |  | isEmailValid (CV:889-902). |
| 80 | `Nominee Mobile Number` | `nominee_mobile_number` | - |  | 10 digits numeric (CV:937-946). |
| 81 | `ABHA ID` | `abha_id` | - |  | Applied when non-null (UpdateJob:821-824). |
| 82 | `Account Number` | `account_number` | - |  | Applied when non-null (UpdateJob:826-829). |

Conditional columns: `Employer Name` (only if child entities), `Family Number` (EDME/EB portals), LOL columns 125-129 (EB/TATA/ALLIANCE_UAT portals).

### 4.3 Variant overlays

| Variant | Applies when | Column -> requirement |
|---|---|---|
| by_endorsement_number | endrosement_number filled | `endrosement_number`: REQUIRED; `type_of_correction`: REQUIRED |
| by_existing_code | endrosement_number blank | `existing_employee_code`: REQUIRED; `relationship_with_employee`: REQUIRED; `type_of_correction`: REQUIRED; `existing_employee_dob`: optional; `existing_insured_member_first_name`: optional; `existing_insured_member_dob`: optional |
| eb_aig_type_dropdown | portal EMPLOYEE_BENEFIT or AIG_ALL | `type_of_correction`: REQUIRED |
| nominee_correction | type_of_correction = "Nominee Details Correction" | `relationship_with_insured`: REQUIRED; `nominee_name`: optional; `appointee_name`: REQUIRED (nominee age < 18); `relation_of_appointee_with_nominee`: REQUIRED (nominee age < 18) |
| number_of_time_suminsured | suminsured sub-type NUMBER_OF_TIME_SUMINSURED(28) | `employee_no_of_time_suminsured`: REQUIRED (suminsured or opd_suminsured supplied) |
| si_correction | type_of_correction = "SI Correction" (label only; not enforced) | `suminsured`: optional; `opd_suminsured`: optional |
| dob_correction | type_of_correction = "DOB Correction" (label only) | `employee_dob`: optional; `insured_member_dob`: optional |
| designation_grade_correction | type_of_correction = "Employee Designation/Grade Correction" (label only) | `employee_designation`: optional; `employee_grade`: optional |

Known validator defects to NOT copy: `Marital Status` check in correction is inverted (errors when the value IS valid, CV:797-799); nominee e-mail branch references an undefined message key (`alternate_email_invalid`, CV:895); `EndorsementCorrectionValidation` lol block tests `LOL_BASE` twice (CV:360).

## 5. Generated addition/removal files (HRMS) - for completeness

`AdditionEndrosementFileGenerationService` / `RemoveEndrosementFileGenerationService` write an xlsx of the dump rows using the format-1/2 default fields (or the policy custom template) with headers `Str::headline(feild_name)` (e.g. "Employee Dob"), only populating 21 / 14 fields (`employee_code..ecard_url`, and for removal `employee_leaving_date`, `endrosement_number`) (`AdditionEndrosementFileGenerationService.php:94-118`, `RemoveEndrosementFileGenerationService.php:94-110`). These are output files, not upload templates.

## 6. ENROLLMENT REPORT (export of enrolled members)

Entry: `EnrollmentReportingJob` (`reportType` 1 standalone, 2 consolidated, 3 TPA consolidated, 4 OPD, 5 standalone+flex; `EnrollmentReportType`, `constant.php:1336-1350`). One row per **member** (per `employee_members` row, including soft-deleted ones, `->withTrashed()`), ordered by `master_employees.code, relation_id`. Dates are `d-m-Y` (`d-m-Y H:i:s` for created/updated/deleted/confirmed timestamps). Filters: policy ids, date window on `employee_members.created_at` (or `start_date` when `employer.report_filter_by=1`), live status (1 active / 2 inactive), inception vs endorsement, midterm, enrollment confirmed.

### 6.1 Consolidated (entity) report - live path `EntityEnrollmentReportingService` (format 32)

Headers (default) = `Str::headline(feild_name)` of the format-32 rows ordered by `sequence` (`EntityEnrollmentReportingService.php:66-68, 476, 1951-1958`); a custom template for the employer (`default_format_id = 32`) replaces them with its own labels. Labels below are EMULATED from `Str::headline` (not executed; UNKNOWN exact), `seed label` is `default_feild_name` (only visible via custom templates). Rows soft-deleted by the seeder are excluded. 220 seeded rows.

| # | Header (derived) | Field | Seed label | Present when |
|---|---|---|---|---|
| 1 | `Employee Code` | `employee_code` | Employee Code | always |
| 2 | `Employee Name` | `employee_name` | Employee First Name | always |
| 3 | `Employee Dob` | `employee_dob` | Employee DOB | always |
| 4 | `Age` | `age` | Age | always |
| 5 | `Doj` | `doj` | Employee Date of Joining | always |
| 6 | `Employee Annual Salary` | `employee_annual_salary` | Employee Annual Salary | always |
| 7 | `Employee Variables In Salary` | `employee_variables_in_salary` | Employee variables In Salary | always |
| 8 | `Number Of Time Salary` | `number_of_time_salary` | Number Of Times Salary | always |
| 9 | `Customer Id` | `customer_id` | Customer Id | always |
| 10 | `Mobile No` | `mobile_no` | Employee Mobile Number | always |
| 11 | `Email` | `email` | Employee Email | always |
| 12 | `Grade` | `grade` | Grade | always |
| 13 | `Designation` | `designation` | Designation | always |
| 14 | `Company (Employer) Name` | `company_(employer)_name` | Company (employer) Name | always |
| 15 | `Parent Employer` | `parent_employer` | Parent Employer | portal EMPLOYEE_BENEFIT\|EDME_ALL only |
| 16 | `Super Parent Employer` | `super_parent_employer` | Super Parent Employer | portal EMPLOYEE_BENEFIT\|EDME_ALL only |
| 17 | `Member First Name` | `member_first_name` | Member First Name | always |
| 18 | `Member Last Name` | `member_last_name` | Member Last Name | always |
| 19 | `Full Name` | `full_name` | Full Name | always |
| 20 | `Limitation Applied` | `limitation_applied` | Limitation Applied | always; header dropped unless a selected policy has premium type FAMILY_PERCENTAGE_WISE(28) (EntityEnrollmentReportingService.php:176-190, used at :474) |
| 21 | `Limitation Amount` | `limitation_amount` | Limitation Amount | always; header dropped unless a selected policy has premium type FAMILY_PERCENTAGE_WISE(28) (EntityEnrollmentReportingService.php:176-190, used at :474) |
| 22 | `Job Band` | `job_band` | Job Band | always |
| 23 | `Family Number` | `family_number` | Family Number | always |
| 24 | `Physical Ecard Consent` | `physical_ecard_consent` | Physical Ecard Consent | portal EMPLOYEE_BENEFIT\|AIG_ALL only |
| 25 | `Physical Ecard Address` | `physical_ecard_address` | Physical Ecard Address | portal EMPLOYEE_BENEFIT\|AIG_ALL only |
| 26 | `Member Dob` | `member_dob` | Member DOB | always |
| 27 | `Member Age` | `member_age` | Member Age | always |
| 28 | `Gender` | `gender` | Gender | always |
| 29 | `Relationship With Employee` | `relationship_with_employee` | Relationship with Employee | always |
| 30 | `Marriage Date` | `marriage_date` | Marriage Date | always |
| 31 | `Employee Unit` | `employee_unit` | Employee Unit | portal EMPLOYEE_BENEFIT\|AIG_ALL only |
| 32 | `Policy Type` | `policy_type` | Policy Type | always |
| 33 | `Policy Sub Type` | `policy_sub_type` | Policy Sub Type | always |
| 34 | `Policy #` | `policy_#` | Policy# | always |
| 35 | `Policy Name` | `policy_name` | Policy Name | always |
| 36 | `Policy Enrollement Start Date` | `policy_enrollement_start_date` | Policy Enrollement Start Date | always |
| 37 | `Policy Enrollement End Date` | `policy_enrollement_end_date` | Policy Enrollement End Date | always |
| 38 | `Cover Start Date (Endorsement Date)` | `cover_start_date_(endorsement_date)` | Cover Start Date (endorsement Date) | always |
| 39 | `Cover End Date` | `cover_end_date` | Cover End Date | always |
| 40 | `Employee Enrollement End Date` | `employee_enrollement_end_date` | Employee Enrollement End Date | always |
| 41 | `Suminsured` | `Suminsured` | Suminsured | always |
| 42 | `Deductible` | `deductible` | Deductible | always |
| 43 | `Ipd Employer Premium` | `Ipd_Employer_Premium` | Ipd Employer Premium | always |
| 44 | `Enhance Suminsured` | `Enhance_suminsured` | Enhance Suminsured | always |
| 45 | `Total Suminsured` | `Total Suminsured` | Total Suminsured | always |
| 46 | `Ipd Employee Premium` | `Ipd_Employee_Premium` | Ipd Employee Premium | always |
| 47 | `Ipd Total Premium` | `Ipd_Total_Premium` | Ipd Total Premium | always |
| 48 | `Is Password Reset` | `is_password_reset` | Is Password Reset | always |
| 49 | `Opd Suminsured` | `Opd_suminsured` | Opd Suminsured | always |
| 50 | `Opd Employee Premium` | `Opd_Employee_Premium` | Opd Employee Premium | always |
| 51 | `Opd Employer Premium` | `Opd_Employer_Premium` | Opd Employer Premium | always |
| 52 | `Opd Total Premium` | `Opd_Total_Premium` | Opd Total Premium | always |
| 53 | `Wallet Balance Allocated` | `wallet_balance_allocated` | Wallet Balance Allocated | always |
| 54 | `Wallet Balance Utilized` | `wallet_balance_utilized` | Wallet Balance Utilizied | always |
| 55 | `Premium Without Gst` | `premium_without_gst` | Premium Without Gst | always |
| 56 | `Gst Amount` | `gst_amount` | Gst Amount | always |
| 57 | `Flex Pay Through Salary Amount` | `flex_pay_through_salary_amount` | Flex Pay Through Salary Amount | always |
| 58 | `Member Id ( T P A)` | `member_id_(TPA)` | Member Id (TPA) | always |
| 59 | `Member Id ( Insurer)` | `member_id_(Insurer)` | Member Id (Insurer) | portal EMPLOYEE_BENEFIT\|AIG_ALL only |
| 60 | `Premium Difference` | `premium_difference` | Premium Difference | always |
| 61 | `Total Premium` | `Total_Premium` | Total Premium | always |
| 62 | `Insurer Name` | `insurer_name` | Insurer Name | always |
| 63 | `Tpa Name` | `tpa_name` | Tpa Name | always |
| 64 | `Endorsement Number` | `endorsement_number` | Endorsement Number | always |
| 65 | `Logged In` | `logged_in` | Logged In | always |
| 66 | `Icici Endorsement Number` | `icici_endorsement_number` | ICICI Endorsement Number | always |
| 67 | `Leaving Date` | `leaving_date` | Leaving Date | always |
| 68 | `Effective Date` | `effective_date` | Effective Date | always |
| 69 | `Endorsement Url` | `endorsement_url` | Endorsement Url | always |
| 70 | `Refunded Premium` | `refunded_premium` | Refunded Premium | always |
| 71 | `Insurer Endorsement Id` | `insurer_endorsement_id` | Insurer Endorsement Id | always |
| 72 | `Correction Effective Date` | `correction_effective_date` | Correction Effective Date | always |
| 73 | `Mid Term Endorsement` | `mid_term_endorsement` | Mid Term Endorsement | always |
| 74 | `Insurer Endorsement Date` | `insurer_endorsement_date` | Insurer Endorsement Date | always |
| 75 | `Deletion Effective Date` | `deletion_effective_date` | Deletion Effective Date | always |
| 76 | `Enrolment Confirmed` | `enrolment_confirmed` | Enrolment Confirmed | always |
| 77 | `Insurer Removal Id` | `insurer_removal_id` | Insurer Removal Id | always |
| 78 | `Enrollment Start Date (When Confirmed)` | `enrollment_start_date_(when_confirmed)` | Enrollment Start Date (When Confirmed) | always |
| 79 | `Member Lockin Days` | `member_lockin_days` | Member Lockin Days | always |
| 80 | `Insurer Correction Id` | `insurer_correction_id` | Insurer Correction Id | always |
| 81 | `Enrollment End Date (When Confirmed)` | `enrollment_end_date_(when_confirmed)` | Enrollment End Date (When Confirmed) | always |
| 82 | `Enrolment Confirmed Date` | `enrolment_confirmed_date` | Enrolment Confirmed Date | always |
| 83 | `Insurer Correction Date` | `insurer_correction_date` | Insurer Correction Date | always |
| 84 | `Is Inception` | `is_inception` | Is Inception | always |
| 85 | `Insurer Removal Date` | `insurer_removal_date` | Insurer Removal Date | always |
| 86 | `Endorsement/Inception Date` | `endorsement/inception_date` | Endorsement/Inception Date | always |
| 87 | `Member Status` | `member_status` | Member Status | always |
| 88 | `Nominee Details` | `nominee_details` | Nominee Details | always |
| 89 | `Type Of Correction` | `type_of_correction` | Type Of Correction | always |
| 90 | `Nominee Declaration Date & Time` | `nominee_declaration_date_&_time` | Nominee Declared At | always |
| 91 | `Ecard Url` | `ecard_url` | Ecard Url | always |
| 92 | `Cover Type` | `cover_type` | Cover Type | always |
| 93 | `Ecard Flag` | `ecard_flag` | Ecard Flag | always |
| 94 | `Installment` | `installment` | Installment | always |
| 95 | `Family Ecard Url` | `family_ecard_url` | Family Ecard Url | always |
| 96 | `Employee Variables In Salary 2` | `employee_variables_in_salary_2` | Employee Variables In Salary 2 | always |
| 97 | `Employee Variables In Salary 3` | `employee_variables_in_salary_3` | Employee Variables In Salary 3 | always |
| 98 | `Employee Annual Salary` | `employee_annual_salary` | Employee Annual Salary | always |
| 99 | `Employee Annual Salary 2` | `employee_annual_salary_2` | Employee Annual Salary 2 | always |
| 100 | `Employee Annual Salary 3` | `employee_annual_salary_3` | Employee Annual Salary 3 | always |
| 101 | `Other 1` | `other_1` | Other 1 | always |
| 102 | `Other 2` | `other_2` | Other 2 | always |
| 103 | `Other 3` | `other_3` | Other 3 | always |
| 104 | `Other 4` | `other_4` | Other 4 | always |
| 105 | `Broker Branch Name` | `broker_branch_name` | Broker Branch Name | always |
| 106 | `Enhance Premium` | `Enhance_premium` | Enhance Premium | always |
| 107 | `Co Insurer Names` | `co_insurer_names` | Co Insurer Names | always |
| 108 | `Opd Refunded Premium` | `opd_refunded_premium` | Opd Refunded Premium | always |
| 109 | `Update Adjusted Premium` | `update_adjusted_premium` | Update Adjusted Premium | always |
| 110 | `Update Opd Adjusted Premium` | `update_opd_adjusted_premium` | Update Opd Adjusted Premium | always |
| 111 | `Created By` | `created_by` | Created By | always |
| 112 | `Updated By` | `updated_by` | Updated By | always |
| 113 | `Updated At` | `updated_at` | Updated At | always |
| 114 | `Deleted At` | `deleted_at` | Deleted At | always |
| 115 | `Deleted By` | `deleted_by` | Deleted By | always |
| 116 | `Newly Wed/ New Born` | `Newly_Wed/New_Born` | Newly Wed/New Born | always |
| 117 | `Employer Verification Status` | `employer_verification_status` | Employer Verification Status | always |
| 118 | `Broker Verification Status` | `broker_verification_status` | Broker Verification Status | always |
| 119 | `Employer Verification Needed` | `employer_verification_needed` | Employer Verification Needed | always |
| 120 | `Broker Verification Needed` | `broker_verification_needed` | Broker Verification Needed | always |
| 121 | `Alternate Email` | `alternate_email` | Alternate Email | always |
| 122 | `Emergency Contact Name` | `emergency_contact_name` | Emergency Contact Name | always |
| 123 | `Emergency Contact Number` | `emergency_contact_number` | Emergency Contact Number | always |
| 124 | `Emergency Contact Email` | `emergency_contact_email` | Emergency Contact Email | always |
| 125 | `Member Email` | `member_email` | Member Email | always |
| 126 | `Member Mob No` | `member_mob_no` | Member Mobile | always |
| 127 | `Address` | `address` | Address 1 | always |
| 128 | `Address 2` | `address_2` | Address 2 | always |
| 129 | `State Name` | `state_name` | State | always |
| 130 | `City Name` | `city_name` | City | always |
| 131 | `Account No` | `account_no` | Bank Account Number | always |
| 132 | `Account Holder Name` | `account_holder_name` | Bank Account Holder Name | always |
| 133 | `Bank Ifsc` | `bank_ifsc` | Bank IFSC | always |
| 134 | `Bank Name` | `bank_name` | Bank Name | always |
| 135 | `Pincode` | `pincode` | Pincode | always |
| 136 | `Created At` | `created_at` | Created At | always |
| 137 | `Guardian Details` | `guardian_details` | Guardian Details | always |
| 138 | `Email Consent` | `email_consent` | Email Consent | always |
| 139 | `Whatsapp Consent` | `whatsapp_consent` | Whatsapp Consent | always |
| 140 | `Sms Consent` | `sms_consent` | SMS Consent | always |
| 141 | `No Of Policy Days Run` | `no_of_policy_days_run` | No Of Policy Days Run | always |
| 142 | `Annual Premium` | `annual_premium` | Annual Premium | always |
| 143 | `Switch Effective Date` | `switch_effective_date` | Switch Effective Date | always |
| 144 | `Switch Remarks` | `switch_remarks` | Switch Remarks | always |
| 145 | `Switch Old Employer` | `switch_old_employer` | Old Employer | always |
| 146 | `License Start Date` | `license_start_date` | License Start Date | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 147 | `License Expiry Date` | `license_expiry_date` | License Expiry Date | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 148 | `Insurer Client Id` | `insurer_client_id` | Insurer Client Id | always |
| 149 | `Copayment` | `copayment` | Copayment | always |
| 150 | `Copayment Percentage` | `copayment_percentage` | Copayment Percentage | always |
| 151 | `Cover Limit Exceed Option` | `cover_limit_exceed_option` | Cover Limit Exceed Option | always |
| 152 | `Type Of License` | `type_of_license` | Type of license | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 153 | `Issuing Authority` | `issuing_authority` | Issuing authority | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 154 | `License Issuance Date` | `license_issuance_date` | License Issuance date | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 155 | `Cover Effective Date` | `cover_effective_date` | Cover Effective Date | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 156 | `License Number` | `license_number` | License Number | portal EMPLOYEE_BENEFIT\|TATA_ALL\|ALLIANCE_UAT only (EntityExportFeildsSeeder.php:17-24) |
| 157 | `Has Death Certificate` | `has_death_certificate` | Has Death Certificate | always |
| 158 | `Death Certificate Url` | `death_certificate_url` | Has Death Certificate URL | always |
| 159 | `New Opd` | `new_opd` | New Opd | always |
| 160 | `New Topup` | `new_topup` | New Topup | always |
| 161 | `New Parental` | `new_parental` | New Parental | always |
| 162 | `Batch Id` | `batch_id` | Batch ID | always |
| 163 | `Member Active Status` | `member_active_status` | Member Active Status | always |
| 164 | `Ctc` | `ctc` | CTC | always |
| 165 | `Is Dummy Email` | `is_dummy_email` | Is Dummy Email | always |
| 166 | `Remarks` | `remarks` | Remarks | always |
| 167 | `Premium Certificate` | `premium_certificate` | premium_certificate | always |
| 168 | `Location` | `location` | location | always |
| 169 | `To Hide Email` | `to_hide_email` | To Hide Email | always |
| 170 | `Zone` | `zone` | Zone | always |
| 171 | `Is Dummy Mobile` | `is_dummy_mobile` | Is Dummy Mobile | always |
| 172 | `To Hide Mobile` | `to_hide_mobile` | To Hide Mobile | always |
| 173 | `Is Married` | `is_married` | Is Married | always |
| 174 | `Is Vip Employee` | `is_vip_employee` | Is VIP Employee | always |
| 175 | `Last Login At` | `last_login_at` | Last Login At | always |
| 176 | `Reason Of Removal` | `reason_of_removal` | Reason of Removal | always |
| 177 | `Others 1` | `others_1` | Others 1 | always |
| 178 | `Others 2` | `others_2` | Others 2 | always |
| 179 | `Others 3` | `others_3` | Others 3 | always |
| 180 | `Member Middle Name` | `member_middle_name` | Member Middle Name | always |
| 181 | `Marital Status` | `marital_status` | Marital Status | always |
| 182 | `Parent Id` | `parent_id` | Parent Id | always |
| 183 | `Category` | `category` | Category | always |
| 184 | `Passport No` | `passport_no` | Passport No | always |
| 185 | `Permit No` | `permit_no` | Permit No | always |
| 186 | `Emirates Id` | `emirates_id` | Emirates Id | always |
| 187 | `File No` | `file_no` | File No | always |
| 188 | `Visa Location` | `visa_location` | Visa Location | always |
| 189 | `Work Location` | `work_location` | Work Location | always |
| 190 | `Nationality` | `nationality` | Nationality | always |
| 191 | `Subgroup` | `subgroup` | Subgroup | always |
| 192 | `Po Box` | `po_box` | Po Box | always |
| 193 | `Remarks` | `remarks` | Remarks | always |
| 194 | `Emp Expiry Date` | `emp_expiry_date` | Emp Expiry Date | always |
| 195 | `Extra Premium Percentage` | `extra_premium_percentage` | Extra Premium Percentage | always |
| 196 | `Fc Extra Premium` | `fc_extra_premium` | Fc Extra Premium | always |
| 197 | `Lc Extra Premium` | `lc_extra_premium` | Lc Extra Premium | always |
| 198 | `Photo` | `photo` | Photo | always |
| 199 | `Commision` | `commision` | Commision | always |
| 200 | `Add Delete` | `add_delete` | Add/delete | always |
| 201 | `Dha Member Id` | `dha_member_id` | Dha Member Id | always |
| 202 | `Birth Certificate Id` | `birth_certificate_id` | Birth Certificate Id | always |
| 203 | `Sponser Assr Code` | `sponser_assr_code` | Sponser Assr Code | always |
| 204 | `Member Type` | `member_type` | Member Type | always |
| 205 | `Visa No` | `visa_no` | Visa No | always |
| 206 | `Visa Date` | `visa_date` | Visa Date | always |
| 207 | `Visa Uid No` | `visa_uid_no` | Visa Uid No | always |
| 208 | `Entry Type` | `entry_type` | Entry Type | always |
| 209 | `Previous Insurer` | `previous_insurer` | Previous Insurer | always |
| 210 | `Previous Pol No` | `previous_pol_no` | Previous Pol No | always |
| 211 | `Previous Pol Exp Dt` | `previous_pol_exp_dt` | Previous Pol Exp Dt | always |
| 212 | `Establishment Entity Id` | `establishment_entity_id` | Establishment Entity Id | always |
| 213 | `Establishment Entity Type` | `establishment_entity_type` | Establishment Entity Type | always |
| 214 | `Submit Apeal Yn` | `submit_apeal_yn` | Submit Apeal Yn | always |
| 215 | `Uhc Transmit Id` | `uhc_transmit_id` | Uhc Transmit Id | always |
| 216 | `Uhc Alt Id` | `uhc_alt_id` | Uhc Alt Id | always |
| 217 | `Visa Issue Date` | `visa_issue_date` | Visa Issue Date | always |
| 218 | `Visa Expiry Date` | `visa_expiry_date` | Visa Expiry Date | always |
| 219 | `Visa Copy Url` | `visa_copy_url` | Visa Copy Url | always |
| 220 | `Passport Copy Url` | `passport_copy_url` | Passport Copy Url | always |

Identifier columns: `Employee Code`, `Relationship With Employee`, `Endorsement Number` (= member `endorsement_id`, `EntityEnrollmentReportingService.php:1266`; the next column `Icici Endorsement Number` is the TPA-side number, `:1267`). Dates `d-m-Y`. `Tpa consolidated` mode adds hidden keys only.

### 6.2 Standalone (single policy) report - live path `StandaloneEnrollmentReportingService`

Row = associative array built at `StandaloneEnrollmentReportingService.php:1415-1595`; **headers = `array_keys(max($finalDataArray))`** (the widest row, `:1918`) -> `ucwords(str_replace("_"," ",key))` -> `formatDateDefaultHeaders` (appends " (DD-MM-YYYY)" / " (DD-MM-YYYY HH:MM:SS)", `:2007-2045`) -> `currencyHeaders` (appends the employer SI/premium currency name, possibly an empty string leaving a trailing space, `:2089-2131`). When the policy has a custom template for format 9 (`CustomFormatController::checkCustomPolicyFormat($policyId, 9)`, `:700-705`) the template labels/order replace all of this (catalog = format 9, JSON `format9_custom_enrollment_catalog`, 273 seeded rows from `CustomenrollmentReportSeeder.php`). Order below is the literal insertion order; conditional keys are appended afterwards in the order listed.

| # | Header (derived) | Field key | Present when |
|---|---|---|---|
| 1 | `Employee Code` | `employee_code` | always |
| 2 | `Employee Name` | `employee_name` | always |
| 3 | `Employee Dob (DD-MM-YYYY)` | `employee_dob` | always |
| 4 | `Age` | `age` | always |
| 5 | `Doj (DD-MM-YYYY)` | `doj` | always |
| 6 | `Employee Annual Salary` | `employee_annual_salary` | always |
| 7 | `Employee Annual Salary 2` | `employee_annual_salary_2` | always |
| 8 | `Employee Annual Salary 3` | `employee_annual_salary_3` | always |
| 9 | `Employee Variables In Salary` | `employee_variables_in_salary` | always |
| 10 | `Employee Variables In Salary 2` | `employee_variables_in_salary_2` | always |
| 11 | `Employee Variables In Salary 3` | `employee_variables_in_salary_3` | always |
| 12 | `Flex Pay Through Salary Amount` | `flex_pay_through_salary_amount` | always |
| 13 | `Number Of Time Salary` | `number_of_time_salary` | always |
| 14 | `Customer Id` | `customer_id` | always |
| 15 | `Mobile No` | `mobile_no` | always |
| 16 | `Email` | `email` | always |
| 17 | `Grade` | `grade` | always |
| 18 | `Designation` | `designation` | always |
| 19 | `Company (employer) Name` | `company_(employer)_name` | always |
| 20 | `Parent Employer` | `parent_employer` | only if portal EMPLOYEE_BENEFIT\|EDME_ALL, else unset (:1600-1603) |
| 21 | `Super Parent Employer` | `super_parent_employer` | only if portal EMPLOYEE_BENEFIT\|EDME_ALL, else unset (:1600-1603) |
| 22 | `Member First Name` | `member_first_name` | always |
| 23 | `Member Last Name` | `member_last_name` | always |
| 24 | `Full Name` | `full_name` | always |
| 25 | `Member Dob (DD-MM-YYYY)` | `member_dob` | always |
| 26 | `Member Age` | `member_age` | always |
| 27 | `Gender` | `gender` | always |
| 28 | `Employee Unit` | `employee_unit` | always |
| 29 | `Relationship With Employee` | `relationship_with_employee` | always |
| 30 | `Marriage Date (DD-MM-YYYY)` | `marriage_date` | always |
| 31 | `Policy Type` | `policy_type` | always |
| 32 | `Policy Sub Type` | `policy_sub_type` | always |
| 33 | `Policy #` | `policy_#` | always |
| 34 | `Policy Name` | `policy_name` | always |
| 35 | `Broker Branch Name` | `broker_branch_name` | always |
| 36 | `Policy Enrolment Start Date (DD-MM-YYYY)` | `policy_enrolment_start_date` | always |
| 37 | `Policy Enrolment End Date (DD-MM-YYYY)` | `policy_enrolment_end_date` | always |
| 38 | `Cover Start Date (endorsement Date) (DD-MM-YYYY)` | `cover_start_date_(endorsement_date)` | always |
| 39 | `Cover End Date (DD-MM-YYYY)` | `cover_end_date` | always |
| 40 | `Employee Enrolment End Date (DD-MM-YYYY)` | `employee_enrolment_end_date` | always |
| 41 | `Custom Enrollment Close Date` | `custom_enrollment_close_date` | always |
| 42 | `Plan Name` | `plan_name` | always |
| 43 | `Suminsured {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Suminsured` | always |
| 44 | `No Of Policy Days Run` | `no_of_policy_days_run` | always |
| 45 | `Enhance Suminsured {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Enhance Suminsured` | always |
| 46 | `Enhance Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Enhance Premium` | always |
| 47 | `Total Suminsured {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Total Suminsured` | always |
| 48 | `Ipd Employer Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Ipd_Employer_Premium` | always |
| 49 | `Ipd Employee Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Ipd_Employee_Premium` | always |
| 50 | `Ipd Total Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Ipd_Total_Premium` | always |
| 51 | `Opd Suminsured {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Opd_suminsured` | always |
| 52 | `Opd Employee Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Opd_Employee_Premium` | always |
| 53 | `Opd Employer Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Opd_Employer_Premium` | always |
| 54 | `Wallet Balance Allocated` | `wallet_balance_allocated` | always |
| 55 | `Wallet Balance Utilized` | `wallet_balance_utilized` | always |
| 56 | `Premium Difference` | `premium_difference` | always |
| 57 | `Opd Total Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Opd_Total_Premium` | always |
| 58 | `Total Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `Total_Premium` | always |
| 59 | `Annual Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `annual_premium` | always |
| 60 | `Member Id (TPA)` | `member_id_(TPA)` | always |
| 61 | `Member Id (Insurer)` | `member_id_(Insurer)` | only if portal EMPLOYEE_BENEFIT\|AIG_ALL, else unset (:1612) |
| 62 | `Insurer Name` | `insurer_name` | always |
| 63 | `Insurer Branch Name` | `insurer_branch_name` | always |
| 64 | `Co Insurer Names` | `co_insurer_names` | always |
| 65 | `Tpa Name` | `tpa_name` | always |
| 66 | `TpaBranchName` | `tpaBranchName` | always |
| 67 | `Cover Type` | `cover_type` | always |
| 68 | `Endorsement Number` | `endorsement_number` | always |
| 69 | `Batch Id` | `batch_id` | always |
| 70 | `Logged In` | `logged_in` | always |
| 71 | `Last Login At (DD-MM-YYYY HH:MM:SS)` | `last_login_at` | always |
| 72 | `Effective Date (DD-MM-YYYY)` | `effective_date` | always |
| 73 | `Correction Effective Date (DD-MM-YYYY)` | `correction_effective_date` | always |
| 74 | `Deletion Effective Date (DD-MM-YYYY)` | `deletion_effective_date` | always |
| 75 | `Enrollment Start Date (when Confirmed) (DD-MM-YYYY)` | `enrollment_start_date_(when_confirmed)` | always |
| 76 | `Enrollment End Date (when Confirmed) (DD-MM-YYYY)` | `enrollment_end_date_(when_confirmed)` | always |
| 77 | `Leaving Date (DD-MM-YYYY)` | `leaving_date` | always |
| 78 | `Refunded Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `refunded_premium` | always |
| 79 | `Opd Refunded Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `opd_refunded_premium` | always |
| 80 | `Mid Term Endorsement` | `mid_term_endorsement` | always |
| 81 | `Enrolment Confirmed` | `enrolment_confirmed` | always |
| 82 | `Member Lockin Days` | `member_lockin_days` | always |
| 83 | `Enrolment Confirmed Date (DD-MM-YYYY)` | `enrolment_confirmed_date` | always |
| 84 | `Is Inception` | `is_inception` | always |
| 85 | `Endorsement/inception Date (DD-MM-YYYY)` | `endorsement/inception_date` | always |
| 86 | `Member Status` | `member_status` | always |
| 87 | `Type Of Correction` | `type_of_correction` | always |
| 88 | `Ecard Url` | `ecard_url` | always |
| 89 | `Ecard Flag` | `ecard_flag` | always |
| 90 | `Familiy Ecard Url` | `familiy_ecard_url` | always |
| 91 | `Nominee Form Pdf Url` | `nominee_form_pdf_url` | always |
| 92 | `Email Consent` | `email_consent` | always |
| 93 | `Sms Consent` | `sms_consent` | always |
| 94 | `Whatsapp Consent` | `whatsapp_consent` | always |
| 95 | `Update Adjusted Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `update_adjusted_premium` | always |
| 96 | `Update Opd Adjusted Premium {premium/SI currency name from EmployerCustomConfigs, "" if none}` | `update_opd_adjusted_premium` | always |
| 97 | `Medium Type` | `medium_type` | always |
| 98 | `Created By` | `created_by` | always |
| 99 | `Updated By` | `updated_by` | always |
| 100 | `Created At (DD-MM-YYYY HH:MM:SS)` | `created_at` | always |
| 101 | `Updated At (DD-MM-YYYY HH:MM:SS)` | `updated_at` | always |
| 102 | `Deleted By` | `deleted_by` | always |
| 103 | `Deleted At (DD-MM-YYYY HH:MM:SS)` | `deleted_at` | always |
| 104 | `Installment` | `installment` | always |
| 105 | `Newly Wed/New Born` | `Newly_Wed/New_Born` | always |
| 106 | `Is Twin Child` | `is_twin_child` | always |
| 107 | `Is Special Child` | `is_special_child` | always |
| 108 | `Is Adopted Child` | `is_adopted_child` | always |
| 109 | `Is Unmarried Child` | `is_unmarried_child` | always |
| 110 | `Employer Verification Status` | `employer_verification_status` | always |
| 111 | `Broker Verification Status` | `broker_verification_status` | always |
| 112 | `Employer Verification Needed` | `employer_verification_needed` | always |
| 113 | `Broker Verification Needed` | `broker_verification_needed` | always |
| 114 | `Insurer Endorsement Id` | `insurer_endorsement_id` | always |
| 115 | `Insurer Endorsemnt Date (DD-MM-YYYY)` | `insurer_endorsemnt_date` | always |
| 116 | `Insurer Correction Id` | `insurer_correction_id` | always |
| 117 | `Insurer Removal Id` | `insurer_removal_id` | always |
| 118 | `Insurer Correction Date (DD-MM-YYYY)` | `insurer_correction_date` | always |
| 119 | `Alternate Email` | `alternate_email` | always |
| 120 | `Emergency Contact Name` | `emergency_contact_name` | always |
| 121 | `Emergency Contact Number` | `emergency_contact_number` | always |
| 122 | `Emergency Contact Email` | `emergency_contact_email` | always |
| 123 | `Member Email` | `member_email` | always |
| 124 | `Member Mobile No` | `member_mobile_no` | always |
| 125 | `Employee Alternate Mobile Number` | `employee_alternate_mobile_number` | always |
| 126 | `Address` | `address` | always |
| 127 | `Address 2` | `address_2` | always |
| 128 | `Employee City Name` | `employee_city_name` | always |
| 129 | `Employee State Name` | `employee_state_name` | always |
| 130 | `Employee Pincode` | `employee_pincode` | always |
| 131 | `Insured City Name` | `insured_city_name` | always |
| 132 | `Insured State Name` | `insured_state_name` | always |
| 133 | `Insured Pincode` | `insured_pincode` | always |
| 134 | `Account No` | `account_no` | always |
| 135 | `Account Holder Name` | `account_holder_name` | always |
| 136 | `Bank Ifsc` | `bank_ifsc` | always |
| 137 | `Bank Name` | `bank_name` | always |
| 138 | `Is Dummy Email` | `is_dummy_email` | always |
| 139 | `To Hide Email` | `to_hide_email` | always |
| 140 | `Is Dummy Mobile` | `is_dummy_mobile` | always |
| 141 | `To Hide Mobile` | `to_hide_mobile` | always |
| 142 | `Is Demo Email` | `is_demo_email` | always |
| 143 | `Demo Email Expiry Date` | `demo_email_expiry_date` | always |
| 144 | `Is Married` | `is_married` | always |
| 145 | `Is Vip Employee` | `is_vip_employee` | always |
| 146 | `Ctc` | `ctc` | always |
| 147 | `Location` | `location` | always |
| 148 | `Zone` | `zone` | always |
| 149 | `Reason Of Removal` | `reason_of_removal` | always |
| 150 | `Others 1` | `others_1` | always |
| 151 | `Others 2` | `others_2` | always |
| 152 | `Others 3` | `others_3` | always |
| 153 | `Tpa Endorsement Error` | `tpa_endorsement_error` | always |
| 154 | `Tpa Endorsement Number` | `tpa_endorsement_number` | always |
| 155 | `Validation Summary` | `validation_summary` | always |
| 156 | `Endorsement Copy Url` | `endorsement_copy_url` | always |
| 157 | `Copayment` | `copayment` | always |
| 158 | `Copayment Percentage` | `copayment_percentage` | always |
| 159 | `Salutation` | `salutation` | always |
| 160 | `Occupation` | `occupation` | always |
| 161 | `Serial Number` | `serial_number` | always |
| 162 | `Aadhar Number` | `aadhar_number` | always |
| 163 | `Abha Id` | `abha_id` | always |
| 164 | `Account Number` | `account_number` | always |
| 165 | `Vpn Id` | `vpn_id` | always |
| 166 | `Cover Effective Date` | `cover_effective_date` | always |
| 167 | `Is Password Reset` | `is_password_reset` | always |
| 168 | `Mobile No Code` | `mobile_no_code` | always |
| 169 | `Job Band` | `job_band` | portal EMPLOYEE_BENEFIT\|EDME_ALL |
| 170 | `Family Number` | `family_number` | portal EMPLOYEE_BENEFIT\|EDME_ALL |
| 171 | `Physical Ecard Consent` | `physical_ecard_consent` | portal EMPLOYEE_BENEFIT\|AIG_ALL |
| 172 | `Physical Ecard Address` | `physical_ecard_address` | portal EMPLOYEE_BENEFIT\|AIG_ALL |
| 173 | `Proposal Number` | `proposal_number` | portal EMPLOYEE_BENEFIT\|AIG_ALL |
| 174 | `Gc Endorsment Number` | `gc_endorsment_number` | portal EMPLOYEE_BENEFIT\|AIG_ALL |
| 175 | `Hr Consent` | `hr_consent` | employer has HR-consent reporting enabled ($includeHrConsent) |
| 176 | `Added From Midterm Approval` | `added_from_midterm_approval` | portal EMPLOYEE_BENEFIT\|TATA_ALL |
| 177 | `Hr Approval By Name` | `hr_approval_by_name` | portal EMPLOYEE_BENEFIT\|TATA_ALL |
| 178 | `Hr Approval By Email` | `hr_approval_by_email` | portal EMPLOYEE_BENEFIT\|TATA_ALL |
| 179 | `Premium Without Gst {currency}` | `premium_without_gst` | policy show_vat_flag != 1 |
| 180 | `Gst Amount {currency}` | `gst_amount` | policy show_vat_flag != 1 |
| 181 | `Premium Without Vat` | `premium_without_vat` | policy show_vat_flag = 1 |
| 182 | `Vat Amount` | `vat_amount` | policy show_vat_flag = 1 |
| 183 | `Cover Limit Exceed Option` | `cover_limit_exceed_option` | policy type TOP_UP |
| 184 | `Limitation Applied` | `limitation_applied` | premium type FAMILY_PERCENTAGE_WISE |
| 185 | `Limitation Amount` | `limitation_amount` | premium type FAMILY_PERCENTAGE_WISE |
| 186 | `License Start Date` | `license_start_date` | LOL policy sub-types 7/8 |
| 187 | `License Expiry Date` | `license_expiry_date` | LOL |
| 188 | `Type Of License` | `type_of_license` | LOL |
| 189 | `Issuing Authority` | `issuing_authority` | LOL |
| 190 | `License Issuance Date` | `license_issuance_date` | LOL |
| 191 | `License Number` | `license_number` | LOL |
| 192 | `Fc Extra Premium {currency}` | `fc_extra_premium` | portal EMPLOYEE_BENEFIT\|TATA_ALL (or Tata block with custom format) |
| 193 | `Lc Extra Premium {currency}` | `lc_extra_premium` | same |
| 194 | `Nominee Declaration Date & Time` | `nominee_declaration_date_&_time` | always (appended after tail blocks) |
| 195 | `Nominee Name` | `nominee_name` | portal EMPLOYEE_BENEFIT\|AIG_ALL |
| 196 | `Nominee Dob` | `nominee_dob` | same |
| 197 | `Nominee Relation Id` | `nominee_relation_id` | same |
| 198 | `Guardian Fname` | `guardian_fname` | same |
| 199 | `Guardian Relation Id` | `guardian_relation_id` | same |
| 200 | `Share Per` | `share_per` | same |
| 201 | `Nominee Mobile Number` | `nominee_mobile_number` | same |
| 202 | `Nominee Email` | `nominee_email` | same |
| 203 | `Deductible` | `deductible` | suminsured sub-type BASED_ON_BASE_SI_AND_TOPUP_PREMIUM(30) |
| 204 | `other_1..other_10` | `other_1..other_10` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 205 | `has_death_certificate` | `has_death_certificate` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 206 | `death_certificate` | `death_certificate` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 207 | `birth_certificate` | `birth_certificate` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 208 | `adopted_children_document` | `adopted_children_document` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 209 | `marriage_certificate` | `marriage_certificate` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 210 | `special_child_certificate` | `special_child_certificate` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 211 | `premium_certificate` | `premium_certificate` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |
| 212 | `Nominee_First_Name_n .. Nominee_pincode_n (12 columns per nominee n)` | `Nominee_First_Name_n .. Nominee_pincode_n (12 columns per nominee n)` | merged at the end: getOthersData / getDeathCertificateData / getNomineeData (SA:1838, 2207-2345) |

Member-identifying columns: `Endorsement Number` (member id), `Employee Code`, `Relationship With Employee`, `Employee Dob (DD-MM-YYYY)`, `Member First Name`, `Member Dob (DD-MM-YYYY)`, `Member Id (TPA)`. Value formats: dates `d-m-Y`; Yes/No flags as text; `Is Inception`/`Mid Term Endorsement` Yes/No; premiums rounded by `PremiumRoundingService` and, on EB/TATA portals, prefixed with the currency text (`StandaloneEnrollmentReportingService.php:1735-1768`). Salaries are masked `*********` on EB/JBBODA_PROD (`:1659-1673`). `StandaloneFlexEnrollmentReportingService` (reportType 5) has the same skeleton plus flex columns: UNKNOWN (not analysed).

### 6.3 OPD enrollment report - `OPDEnrollmentReportService` (one row per EMPLOYEE, wide)

Headers: `Company Name`, `Enrollment Date`, `Plan Name`, `Wallet Amount`, `Plan Type`, `Premium`, `Employee Name`, `Date of Joining`, `Employee DOB`, `Employee Email ID`, `Employee Mobile no.`, `Employee gender`, then per insured member n: `Insured n Name (First name, last name)`, `Relationship with Employee` (rewritten from "Relationship with Employee n"), `Insured n gender`, `Insured n DOB` (`OPDEnrollmentReportService.php:206-243`). Not member-per-row, so unusable as a REMOVE/UPDATE source.

### 6.4 Other column catalogs (full exact lists in JSON)

* `format9_custom_enrollment_catalog` - `CustomenrollmentReportSeeder.php` (273 rows, labels such as "Employee First Name", "Employee Date of Joining", "Policy Enrollement Start Date", "Cover Start Date (endorsement Date)", "Member Id (TPA)"); also read by `OptimizedEnrollmentReport` (internal keys as headers) and by custom templates of the standalone report.
* `module_report_fields_dead_path_*` - `ReportFieldSeeder.php` (180 labels like "Employee DOB (DD-MM-YYYY)", "Insured Member DOB (DD-MM-YYYY)", "Endorsement Number"; sub-type 1 and 2 identical, OPD 12). Dead path; additionally `EnrollmentReportHelper::getFields` queries a `report_type` column that migration `2025_03_25_235319_alter_table_rename_enrollment_reports_to_reports.php` renamed to `report_type_id`, so it would fail if enabled.

## 7. Feeding an enrollment report back as a REMOVE / UPDATE source

There is no automatic path in the legacy app; a broker edits the exported file by hand. What works:

* Choose the **Standalone** (single policy) report or Consolidated report with *live status = Active* (otherwise already-deleted members come out and fail with "Member is already inactive."; `StandaloneEnrollmentReportingService.php:244-251`, `EntityEnrollmentReportingService` `checkActiveStatus`).
* The upload is matched by **slugged header**, so report headers must be renamed to the target sheet labels (a column called `Employee Dob (DD-MM-YYYY)` slugs to `employee_dob_dd_mm_yyyy`, which is not a column of `endrosement_data_dumps`; the model is `$guarded = []` and rows are inserted with `create()`, so **any header whose slug is not a dump column fails the whole upload as INVALID_SHEET** (`EndrosementDataDump.php`, `EndorsementDumpImportJob.php:218,254,272-292`). Delete or rename every non-matching report column before uploading). The member key header must be spelled **`Endrosement Number`** (report says `Endorsement Number` -> slug `endorsement_number`, ignored).

### 7.1 REMOVE

| Report header (standalone) | REMOVE header | Field | Note |
|---|---|---|---|
| Endorsement Number | `Endrosement Number` | `endrosement_number` | Value = employee_members.endorsement_id (8 letters). Header MUST be renamed: slug of "Endorsement Number" is endorsement_number, importer reads endrosement_number (IMP:115,174). |
| Employee Code | `Employee Code` | `employee_code` |  |
| Relationship With Employee | `Relationship with Employee` | `relationship_with_employee` |  |
| Employee Dob (DD-MM-YYYY) | `Employee DOB` | `employee_dob` | d-m-Y text; optional. |
| Member First Name | `Insured Member First Name` | `insured_member_first_name` |  |
| Member Dob (DD-MM-YYYY) | `Insured Member DOB` | `insured_member_dob` |  |
| Member Id (TPA) | `Tpa Member Id` | `tpa_member_id` |  |
| (not in report) | `Employee Leaving Date` | `employee_leaving_date` | MANDATORY, must be supplied by the broker. The report has "Leaving Date" but that is the already-stored date, not an input. |

Minimum viable REMOVE row: `Endrosement Number` + `Employee Leaving Date` (+ `Reason of Removal` for lock-in policies, `Has Death Certificate` Yes/No for parents when the policy requires it). Alternative without the id: `Employee Code` + `Relationship with Employee` + `Employee Leaving Date` (add dob/first name for children/siblings with duplicates).

### 7.2 UPDATE

| Report header (standalone) | UPDATE header | Field | Note |
|---|---|---|---|
| Endorsement Number | `Endrosement Number` | `endrosement_number` |  |
| Employee Code | `Existing Employee Code` | `existing_employee_code` | Only if endrosement_number blank. |
| Employee Dob (DD-MM-YYYY) | `Existing Employee DOB` | `existing_employee_dob` |  |
| Member First Name | `Existing Insured Member First Name` | `existing_insured_member_first_name` |  |
| Member Dob (DD-MM-YYYY) | `Existing Insured Member DOB` | `existing_insured_member_dob` |  |
| Relationship With Employee | `Relationship with Employee` | `relationship_with_employee` |  |
| (not in report) | `Type Of Correction` | `type_of_correction` | MANDATORY. The report has a "Type Of Correction" column but it is a derived output (stored label or a computed list of changed fields, SA:807-870), not an input. |

Then add only the columns to change (e.g. `Insured Member DOB`, `Suminsured`, `Employee Grade`) with `Type Of Correction`; untouched columns must be left blank (blank = no change).

### 7.3 Caveats / bugs found while tracing

* The dead `app/Modules/Reports/EnrollmentReports/EnrollmentReport.php` assigns `endorsement_number` twice and the second assignment (`:376`) overwrites the member id with `employee_members.endorsement_number` (ICICI/TPA number). The live services do not have this bug (`EntityEnrollmentReportingService.php:1266-1267` keeps them separate: `endorsement_number` vs `icici_endorsement_number`). If the rebuild ports the module classes it must keep `endorsement_id` as the exported "Endorsement Number".
* Standalone: `employee_dob` is the *employee* DOB even on dependant rows; `Member Dob` is the member's. For a REMOVE of a dependant send `Insured Member DOB` from `Member Dob`.
* Consolidated ("entity") headers are `Str::headline` of internal keys, so it exposes odd labels ("Policy #", "Member Id ( T P A)"); the Standalone labels are friendlier. Prefer Standalone as the feed-back source.
* Report cell values for gender/relationship are the stored text (Male/Female/Other, RelationMaster names), which are valid inputs for the sheets (AV gender list). Dates are `d-m-Y` strings: valid input.
* Legacy sample files in `public/SampleFormat/` (`sample_employee_data_upload_32`, `insurer_member_endorsement_50`, `opd_add_member_36`, `opd_correction_member_37`) are stale and do not match the seeded labels (JSON `LEGACY_SAMPLE_XLSX`).

## 8. UNKNOWN / not traced

* Custom per-policy/broker/employer templates (DB only): header sets, mandatory marks, label overrides.
* Exact tie order for equal `sequence` values in the default sample (DB-dependent); the lists here are ordered by (sequence, id).
* Exact Excel header strings of the consolidated/standalone reports on a live DB (derived by emulation here; currency suffix and `Str::headline` quirks unverified).
* `StandaloneFlexEnrollmentReportingService` headers; `MultiPolicy` (format 40) and `MultiEmployer` (50) sample sheets beyond the seeded rows in JSON `OTHER_FORMATS_REFERENCE`; `SingleEmployeeEndorsement*Validation`.
* Validation of: address/city/pin on ADD, flex_plan, self_si_selection, member_existing_claim, salutation, occupation, abha_id, account_number, premiums on ADD/UPDATE, `Insurer *` ids/dates semantics, TPA columns on ADD.
* Full `validateDeathCertificate` decision table (AV:3140-3390) and parent cross-selection edge cases.
* Whether the UI limits columns per `Type Of Correction` value (backend does not).
