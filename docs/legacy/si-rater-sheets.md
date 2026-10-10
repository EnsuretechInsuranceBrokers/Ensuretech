# Legacy sum-insured rater upload sheets

Source repo: `/home/user/udogg/wrapper/eb-backend_copy`. Importer = `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php` (I:), top-up importer = `app/Imports/PolicyCustomRateImport.php` (C:), controller = `app/Http/Controllers/Broker/v1/PolicyConfigurationController.php` (PCC:). Sample files in `public/SampleFormat/`. Machine-readable twin: `si-rater-sheets.json`. Anything not verified is marked UNKNOWN.

## Global importer rules

- **headingSlug**: WithHeadingRow + WithGroupedHeadingRow; config/excel.php:60 formatter='slug' => Str::slug(header,'_') (lower-case, whitespace/hyphen->underscore, trims). So any spelling that slugs equal is accepted: 'Sum Insured','sum insured','SUM_INSURED' => sum_insured. 'Suminsured' => suminsured (different key!).
- **sheets**: Only sheet index 0 is imported (sheets() returns a single instance, PolicyConfiguratorSumInsuredImport.php:59-64). SkipsEmptyRows. chunkSize 5.
- **noExplicitValidation**: No WithValidation; failures surface as PHP ErrorException (undefined array key) caught by controller => {status:false,message:'Please upload proper excel file'} (PolicyConfigurationController.php:2853, 2963, 3038) or Exception => {status:false, errors:<message>}.
- **flagParsing**: {"can_add_parent*": "absent/blank=>1; 'Y'/'YES' (case-insens.)=>1; any other non-null=>0 (I:93-106)", "claim_rate": "in ['Yes','Y','yes','y',1,'1'] => 1 else 0 (e.g. I:267-270). 'YES'/'Y ' uppercase 'YES' NOT matched", "isOpd": "is_opd_rate=1 iff raterType==RaterType::OPD (I:75-79). 'Is Opd Sum Insured' sheet column is never read."}
- **ageBand**: getAgeBand (I:944-975): spaces removed; '>N'->'N-120'; '<N'->'0-N'; all digits 'N'->'N-N'; otherwise verbatim (e.g. '18-25').
- **construct**: getConstructCount (app/Helpers/helper.php:6784-6843): '2A + 2C' -> no_of_adult=2,no_of_children=2; max single digit.
- **familyRelationTokens**: E=Self(1),S=Spouse(2),C=Daughter(3)+Son(4),P=Father(5)+Mother(6),PIL=7+8,PR=Partner(10),SB=Brother(12)+Sister(15) (I:977-1013; ids from app/Helpers/constant.php Relation:185-210)
- **commonColumnsWritten**: ["policy_id", "cover_type", "is_opd_rate", "is_exceptional_rate", "exceptional_rate_id", "ip_address", "created_by"]
- **preImportDelete**: Generic branch deletes prior IPD rates for the policy before import: si_file => where relation_permilly NULL, total_premium NULL, is_opd_rate=0, is_enchance_flex_rate=0, is_exceptional_rate=0 (PCC:2834); in_suminsured_file additionally cover_type NULL (PCC:2943); opd_si_file => is_opd_rate=1 (PCC:3022).

## Variants (SI type, IPD/OPD, enhance/flex, top-up, exceptional)

- **siType**: {"1 Individual": "coverType NULL (constant.php SuminsuredType:25-29); single si_file", "2 Family Cover": "coverType NULL; single si_file", "3 Individual+Family": "two slots: si_file => cover_type=2 (FAMILY) (PCC:2832-2843); in_suminsured_file => cover_type=1 (INDIVIDUAL) (PCC:2941-2953) uploadType 19 (PCC:2948); create endpoint: suminsured_file => FAMILY (PCC:5170-5176), in_suminsured_file (PCC:5272-5332). Same sheet layout for both slots."}
- **ipdVsOpd**: OPD sheet uploaded via opd_si_file with opd_suminsured_type/sub_type (PCC:2970-3040, 5385-5475); importer gets raterType=OPD => is_opd_rate=1. Same layout as IPD for each subtype. Samples with an 'Is Opd Sum Insured' Y/N column: ipd_opd_age_suminsured_sample_38.xlsx (Age,Sum Insured,Is Opd Sum Insured; subtype 2), ipd_opd_grade_wise_suminsured_sample_40.xlsx (Grade; subtype 3), ipd_opd_designation_wise_suminsured_sample_41.xlsx (Designation; subtype 4), ipd_opd_member_suminsured_sample_49.xlsx (relation,suminsured ; subtype 6): column ignored by importer. BUG in legacy: OPD member-wise (17) re-import at PCC:3016 uses $request->si_file instead of opd_si_file.
- **enhanceFlex**: No sheet. Enhance/flex SI comes from form array in_sum_insured[].{value,is_enchance_flex_rate=1} => flat_suminsured rows with is_enchance_flex_rate=1 (PCC:2124, 2503-2515, 2580-2590). Validation rule PCC:2124.
- **topUp**: topup_si_file (PCC:5343-5380): subtype 8/30 => PolicyCustomRateImport on the top-up policy; any other subtype => PolicyConfiguratorSumInsuredImport(configuredTopupPolicyId, topup_suminsured_type, topup_suminsured_sub_type). Sheet layouts identical to the base-policy ones.
- **exceptionalRate**: exceptional_rates[i].si_file => PolicyConfiguratorSumInsuredImport with exceptionalRateId; rows get is_exceptional_rate=1, exceptional_rate_id; coverType/raterType NULL (PCC:914-925, 3366-3376).

## Subtype index

| id | name | sample file | layout | header rows |
|---|---|---|---|---|
| 1 | Flat | - | no-sheet | 0 |
| 2 | Age | public/SampleFormat/age_wise_si_5.xlsx | flat-table | 1 |
| 3 | Grade | public/SampleFormat/grade_wise_si_6.xlsx | flat-table | 1 |
| 4 | Designation | public/SampleFormat/designation_wise_si_7.xlsx | flat-table | 1 |
| 5 | Number of times salary | - | no-sheet | 0 |
| 6 | Member | public/SampleFormat/ipd_member_suminsured_sample_48.xlsx | flat-table | 1 |
| 7 | State Wise | public/SampleFormat/state_wise_si_51.xlsx | flat-table | 1 |
| 8 | Base Wise SI & Premium | - | matrix | 1 |
| 9 | Family Construct Wise | public/SampleFormat/family_construct_si_sample_59.xlsx | flat-table | 1 |
| 13 | New Grade With Construct | public/SampleFormat/new_grade_with_family_construct_si_68.xlsx | flat-table | 1 |
| 14 | Designation With Construct | public/SampleFormat/designation_with_family_construct_si_70.xlsx | flat-table | 1 |
| 15 | Relation Wise Number of Time Salary | public/SampleFormat/relation_wise_number_of_salary_72.xlsx | flat-table | 1 |
| 16 | No Rule | - | no-sheet | 0 |
| 17 | Member Wise Multiple & Different | public/SampleFormat/member_wise_different_si.xlsx | matrix | 1 |
| 18 | Grade Wise Number of Time Salary | public/SampleFormat/grade_wise_number_of_time_salary_sample_78.xlsx | flat-table | 1 |
| 20 | Member Count Wise | public/SampleFormat/member_count_wise_si_sample_76.xlsx | flat-table | 1 |
| 22 | Grade + Designation + Age + Construct (GDAC) | public/SampleFormat/gdac_suminsured_80.xlsx | flat-table | 1 |
| 23 | LOL Grade of Employee | public/SampleFormat/only_grade_si_lol.xlsx | flat-table | 1 |
| 24 | Flat (Fixed SI) [LOL] | - | no-sheet | 0 |
| 25 | LOL Age of Employee | public/SampleFormat/age_si_lol.xlsx | flat-table | 1 |
| 26 | LOL Grade With Construct | public/SampleFormat/grade_with_construct_si_lol.xlsx | flat-table | 1 |
| 27 | LOL Number Of Times Salary | public/SampleFormat/grade_wise_number_of_time_salary_si_lol.xlsx | flat-table | 1 |
| 28 | No Of Times Suminsured | - | no-sheet | 0 |
| 29 | Based on Base Policy | - | no-sheet | 0 |
| 30 | Based on Base SI & Topup Premium | public/SampleFormat/base-on-base-si-and-premium-sample.xlsx | matrix | 2 |
| 31 | Percentage of Salary | public/SampleFormat/percentage_of_salary_sample.xlsx | flat-table | 1 |

Ids not in the requested set but present in constant.php: 10, 11, 12 (removed; seeder status 0, samples 66/67/64).

## Per-subtype detail

### 1 - Flat

- Sample: `None` (sample_type_id None); layout `no-sheet`; header rows 0; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Not sheet-driven. Rows come from form array sum_insureds[].value -> master_policy_rates.flat_suminsured (one row per value; Individual/Family: enhance-flex rows flagged is_enchance_flex_rate=1). Seeder sample_type_id=NULL (database/seeds/MasterSumInsuredSubTypeSeeder.php id=1). Importer has no FLAT branch (PolicyConfiguratorSumInsuredImport.php:109-921). Controller: PolicyConfigurationController.php:2484-2515. If a file is uploaded with subtype 1 the importer's model() returns null (nothing inserted).

### 2 - Age

- Sample: `public/SampleFormat/age_wise_si_5.xlsx` (sample_type_id 5); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: One row per age band. Only first sheet imported (sheets() returns one instance, :59-64). Heading row = row 1, slugged by Laravel Excel (config/excel.php:60 formatter=slug => lower-case, spaces->underscore). is_opd_rate from raterType. No explicit validation; missing key raises PHP ErrorException which controller maps to 'Please upload proper excel file' (PCC:2853).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Age | age | age | string age band 'a-b' ; getAgeBand(): '<18'->'0-18', '>80'->'80-120', digits only '30'->'30-30', spaces stripped, otherwise stored verbatim | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:109-124,944-975 |
| Sum Insured | sum_insured | age_suminsured | number ; Heading slugs to sum_insured (underscore). 'Suminsured' (no space) would NOT match -> ErrorException undefined array key | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:109-124 |

### 3 - Grade

- Sample: `public/SampleFormat/grade_wise_si_6.xlsx` (sample_type_id 6); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: can_add_parent / can_add_parent_in_laws derived: column absent or blank => 1 (I:93-106); written always for GRADE (I:141-142). Same grade may repeat on several rows (multiple SIs per grade).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Grade | grade | grade_id | string -> addGrade() firstOrCreate MasterGrades.grade (helper.php:7189); blank -> NULL | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:126-148 |
| Sum Insured | sum_insured | grade_suminsured | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:126-148 |
| (not in sample) Can Add Parent | can_add_parent | can_add_parent | flag: Y/YES (case-insens.) => 1; any other non-null value => 0; column absent/blank => 1 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:93-106,126-148 |
| (not in sample) Can Add Parent In Laws | can_add_parent_in_laws | can_add_parent_in_laws | same as can_add_parent | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:93-106,126-148 |

### 4 - Designation

- Sample: `public/SampleFormat/designation_wise_si_7.xlsx` (sample_type_id 7); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: No can_add_parent columns are written for DESIGNATION.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Designation | designation | designation_id | string -> addDesignation() firstOrCreate | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:150-170 |
| Sum Insured | sum_insured | des_suminsured | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:150-170 |

### 5 - Number of times salary

- Sample: `None` (sample_type_id None); layout `no-sheet`; header rows 0; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Not sheet-driven for the generic subtype: form fields no_of_times_of_salary/min_si_limit/max_si_limit/rounding_type/rounding_unit (single) or salary JSON array (for GPA/GTL/voluntary PA/term-life top-up policy sub types) -> master_policy_rates.no_of_times_of_salary, min_si_limit, max_si_limit, rounding_type, rounding_unit (PCC:2698-2733). Seeder sample_type_id=NULL. Importer has no branch for 5.

### 6 - Member

- Sample: `public/SampleFormat/ipd_member_suminsured_sample_48.xlsx` (sample_type_id 48); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Same relation may repeat for multiple SI options (sample rows 2-15). Sample also has OPD rows (Self 5000, Spouse 10000, Son 1000, Partner 10000 at rows 16-19) in the IPD sample; they are NOT distinguished by the importer. OPD variant sample ipd_opd_member_suminsured_sample_49.xlsx adds a 3rd column 'Is Opd Sum Insured' (Y/N) that the importer never reads (grep: no reference in app/); OPD vs IPD is decided only by which upload slot is used (opd_si_file => raterType OPD => is_opd_rate=1, I:71-76).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| relation | relation | relation_id | string; RelationMaster.name = ucfirst(value) (exact DB name e.g. Self, Spouse, Son, Daughter, Father, Mother, Father-in-law, Mother-in-law, Siblings, Partner: database/seeds/MasterRelationSeeder.php:24-113); unmatched -> relation_id NULL (no error) | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:172-184 |
| suminsured | suminsured | relation_sum_insured | number ; Heading 'suminsured ' has trailing space in sample; slug trims. Here the key is 'suminsured' (NOT sum_insured). | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:172-184 |

### 7 - State Wise

- Sample: `public/SampleFormat/state_wise_si_51.xlsx` (sample_type_id 51); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Sample lists 39 states/UTs incl. duplicates (Gujrat/Gujarat, Odisha/Orissa, Uttaranchal) and trailing space 'Arunachal Pradesh ' (value not trimmed => would create a separate state). Header key is 'suminsured'.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| State | state | state_id | string; MasterState.state_name = ucfirst(value); if not found a NEW MasterState is created (state_code=first 3 chars) - no error | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:236-260 |
| Suminsured | suminsured | state_suminsured | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:236-260 |

### 8 - Base Wise SI & Premium

- Sample: `None` (sample_type_id None); layout `matrix`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: TOP-UP policy sheet, parsed by PolicyCustomRateImport (ToCollection, NO heading row: raw cell grid) not the heading-row importer (PCC:2764-2781, 5170-5181). No sample file exists in public/SampleFormat (seeder sample_type_id=NULL, MasterSumInsuredSubTypeSeeder.php id=8); the closest shipped sample is the subtype-30 workbook. Sheet semantics are decided by the BASE policy's suminsured_subtype_type_id (C:46) when topup subtype != 30 (C:201 else-branch). Row 0 is skipped as header (C:203-207). Rows whose column index 1 equals literal 'suminsured' are SI-header rows holding top-up SI values across columns 2.. (C:213-217); all other non-empty rows are premium rows: col0 = key (age band / grade / designation / relation name / state name; unused for FLAT), col1 = base_suminsured, col2.. = premium aligned under the SI-header row's columns (C:250-320). Rows after the LAST SI-header row are attached to it; only the last SI block collects premium rows (C:233-240) - effectively ONE SI-header block per sheet is supported. Cells equal to 'NA' or null SI are skipped (C:276). Base-subtype variants handled: FLAT(1), AGE(2), GRADE(3), DESIGNATION(4), MEMBER(6), STATE_WISE(7); others produce no rows (UNKNOWN/unsupported). For GRADE/DESIGNATION/MEMBER/STATE the label in col0 is matched against base policy's existing rates (by name) to get ids; an unmatched label silently reuses the previous iteration's id/undefined (C:322-376, no error).
- tax: tax% from request premium_tax when premium_tax_type==1 else 0 (PCC:2755-2758). total = premium + premium*tax/100; if tax==0 tax stored NULL (C:259-270).
- derived: {"top_up_premium": "premium*(1+tax/100)", "total_premium": "same as top_up_premium", "tax": "tax% or NULL"}
- matrix: {"headerRows": 1, "siHeaderRow": "row where col B == literal 'suminsured'; cols C.. = top-up SI values", "rowKeyCol": "A (age/grade/designation/relation/state; FLAT unused)", "baseSiCol": "B", "cellMeaning": "premium (pre-tax) for (rowKey, baseSI, topUpSI)", "terminators": "'NA' cell skipped; null SI skipped"}
- writes: ["policy_id(topup)", "base_suminsured", "top_up_suminsured", "age|grade_id|designation_id|relation_id|state_id", "tax", "top_up_premium", "total_premium", "ip_address"]

### 9 - Family Construct Wise

- Sample: `public/SampleFormat/family_construct_si_sample_59.xlsx` (sample_type_id 59); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Several rows may share a construct (sample has two '1A' rows with different total cover). Sample columns: Family Construct, Total Cover, Self, Spouse, Per Kid, Per Parent, Set of Parents, Per Parent In Law, Set of Parent In Laws, Partner. Cover columns NULL if blank. No can_add_parent written. Construct raters list: SuminsuredSubType::CONSTRUCT_RATERS=[9,13,14,22] (app/Helpers/constant.php).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Family Construct | family_construct | no_of_adult + no_of_children | string like '1A','2A','2A + 2C','4A + 2C'; spaces stripped, split on '+'; getConstructCount() (helper.php:6784-6843): <digit>A adults, <digit>C children, bare 'A'/'C' = 1; blank => 0/0 | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:262-300 |
| Total Cover | total_cover | total_cover | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:262-300 |
| (not in sample) Claim Rate | claim_rate | is_claim_rate | flag: Yes/Y/yes/y/1 => 1 else 0 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:267-270 |
| Self | self | self_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Spouse | spouse | spouse_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Partner | partner | partner_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Per Kid | per_kid | kids_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Per Parent | per_parent | single_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Per Parent In Law | per_parent_in_law | single_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Set of Parents | set_of_parents | double_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| Set of Parent In Laws | set_of_parent_in_laws | double_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |
| (not in sample) Per Siblings | per_siblings | others_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:289-298 |

### 13 - New Grade With Construct

- Sample: `public/SampleFormat/new_grade_with_family_construct_si_68.xlsx` (sample_type_id 68); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Second sheet 'T&C' in sample documents the Family Relation mode (ignored by importer - only sheet 0 read). T&C text: pass Is Family Relation Max Capping=0 if SI is single (Self only), 1 if SI is per-relation; Family Relation column defines relations allowed for that grade. Quirk: guard strlen($row['family_relation'] >= 1) is always truthy (I:352).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Grade | grade | grade_id | string -> addGrade() | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:303-310 |
| Family Construct | family_construct | no_of_adult + no_of_children | as subtype 9; per T&C note leave BLANK when using Family Relation (=> 0/0) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:303-337 |
| Is Family Relation Max Capping | is_family_relation_max_capping | is_family_relation_max_capping | 0/1 raw value copied (only if cell non-null) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:344-346 |
| Family Relation | family_relation | family_relation_id | CSV of tokens E,S,C,P,PR,SB,PIL (case-insens.) -> relation ids comma string, sorted: E=1, S=2, C=3,4, P=5,6, PR=10, SB=12,15, PIL=7,8 (getFamilyRelationIds I:977-1018). NOTE no token for plain 'PL'; digits prefixes (2P) NOT supported here (unlike GDAC) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:348-362 |
| Total Cover | total_cover | total_cover | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:370 |
| (not in sample) Claim Rate | claim_rate | is_claim_rate | flag: Yes/Y/yes/y/1 => 1 else 0 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:316-319 |
| (not in sample) Can Add Parent | can_add_parent | can_add_parent | flag: Y/YES (case-insens.) => 1; any other non-null value => 0; column absent/blank => 1 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:340-346 |
| (not in sample) Can Add Parent In Laws | can_add_parent_in_laws | can_add_parent_in_laws | same as can_add_parent | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:340-346 |
| Self | self | self_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Spouse | spouse | spouse_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Partner | partner | partner_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Per Kid | per_kid | kids_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Per Parent | per_parent | single_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Per Parent In Law | per_parent_in_law | single_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Set of Parents | set_of_parents | double_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| Set of Parent In Laws | set_of_parent_in_laws | double_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |
| (not in sample) Per Siblings | per_siblings | others_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:364-371 |

### 14 - Designation With Construct

- Sample: `public/SampleFormat/designation_with_family_construct_si_70.xlsx` (sample_type_id 70); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: can_add_parent / can_add_parent_in_laws always written (default 1). No family_relation/max-capping handling for designation.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Designation | designation | designation_id | string -> addDesignation() | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:374-381 |
| Family Construct | family_construct | no_of_adult + no_of_children | as subtype 9 | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:374-426 |
| Total Cover | total_cover | total_cover | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:384,420 |
| (not in sample) Claim Rate | claim_rate | is_claim_rate | flag: Yes/Y/yes/y/1 => 1 else 0 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:386-392 |
| (not in sample) Can Add Parent | can_add_parent | can_add_parent | flag: Y/YES (case-insens.) => 1; any other non-null value => 0; column absent/blank => 1 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:93-106,400-401 |
| (not in sample) Can Add Parent In Laws | can_add_parent_in_laws | can_add_parent_in_laws | same as can_add_parent | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:93-106,400-401 |
| Self | self | self_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Spouse | spouse | spouse_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Partner | partner | partner_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Per Kid | per_kid | kids_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Per Parent | per_parent | single_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Per Parent In Law | per_parent_in_law | single_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Set of Parents | set_of_parents | double_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| Set of Parent In Laws | set_of_parent_in_laws | double_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |
| (not in sample) Per Siblings | per_siblings | others_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:412-421 |

### 15 - Relation Wise Number of Time Salary

- Sample: `public/SampleFormat/relation_wise_number_of_salary_72.xlsx` (sample_type_id 72); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Relation may repeat with different multipliers. rounding_type/rounding_unit not in sheet. 'SET FOREIGN_KEY_CHECKS=1' after return is unreachable (I:447).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Relation | relation | relation_id | string; RelationMaster.name exact (NO ucfirst here, I:431); sample uses 'Father-In-Law' - match depends on DB collation (UNKNOWN); unmatched => NULL (FK checks disabled I:432) | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:428-450 |
| No of Time Salary | no_of_time_salary | no_of_times_of_salary | number (multiplier) | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:428-450 |
| Min SI Limit | min_si_limit | min_si_limit | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:428-450 |
| Max SI Limit | max_si_limit | max_si_limit | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:428-450 |

### 16 - No Rule

- Sample: `None` (sample_type_id None); layout `no-sheet`; header rows 0; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: No upload sheet: seeder sample_type_id=NULL and no importer branch (UNKNOWN how rates are produced; grep of PCC finds no NO_RULE handling).

### 17 - Member Wise Multiple & Different

- Sample: `public/SampleFormat/member_wise_different_si.xlsx` (sample_type_id 75); layout `matrix`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Matrix: row 1 = headers (Relationship + N identical 'suminsured' columns); each row = one relation, SI options across columns. Sample: Self 1000000/2000000; Spouse blank; Daughter 100000/200000; Son blank; Parents 300000/400000. Needs >=2 'suminsured' columns for array form (single column would be scalar: UNKNOWN, not verified in vendor - vendor/ absent). Controller pre-pass builds simplifiedRows[relation]={hasNoSiDetail,suminsured[]} then importer reuses it (PCC:2786-2826). hasNoSiDetail=1 relations (all cells blank) are allowed only for Family/Individual+Family si_type; they insert rows with relation_sum_insured NULL (I:529-543,606-616). When si_type != Individual and relation has SI and is father/mother (or parents): also sets double_parent_suminsured = SI (I:578-583); in-laws => double_parent_in_law_suminsured (I:584-587). For Individual si_type (1) with parents, double_parent_suminsured=NULL (I:490-505).
- matrix: {"headerRows": 1, "rowKeyCol": "A relationship", "valueCols": "B.. repeated 'suminsured' headers; each non-blank cell => one master_policy_rates row"}
- validationErrors: ["'Cannot keep both parents & father/mother in suminsured excel' (PCC:2810, 2919, 5211, 5303)", "'Cannot keep both parents-in-law & father-in-law/mother-in-law in suminsured excel' (PCC:2814)", "'Invalid relations in suminsured excel' (PCC:2818)", "'Suminsured cannot be empty in case of Individual type suminsured' (PCC:2822; OPD variants 'opd suminsured excel' PCC:3001-3013; 'OPD suminsured cannot be empty ...' PCC:5449)"]

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Relationship | relationship | relation_id | string lower-cased; allowed (case-insens): self, spouse, partner, father, mother, father-in-law, mother-in-law, parents, parents-in-law, daughter, son, siblings (+ brother, sister on the create endpoint PCC:5152); 'parents' => father+mother, 'parents-in-law' => father-in-law+mother-in-law rows | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:483-621; PCC:2746 |
| suminsured (repeated) | suminsured | relation_sum_insured | each repeated 'suminsured' column is one SI option; WithGroupedHeadingRow makes duplicate headings an array $row['suminsured'][] (I:34); blank cells skipped unless relation has all-blank SI and policy si_type != Individual | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:483-621 |

### 18 - Grade Wise Number of Time Salary

- Sample: `public/SampleFormat/grade_wise_number_of_time_salary_sample_78.xlsx` (sample_type_id 78); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Grade repeats allowed (multiple multipliers). FK checks disabled before insert.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Grade | grade | grade_id | string -> addGrade(); blank -> NULL | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:452-481 |
| No of Time Salary | no_of_time_salary | no_of_times_of_salary | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:452-481 |
| Min SI Limit | min_si_limit | min_si_limit | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:452-481 |
| Max SI Limit | max_si_limit | max_si_limit | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:452-481 |

### 20 - Member Count Wise

- Sample: `public/SampleFormat/member_count_wise_si_sample_76.xlsx` (sample_type_id 76); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Unrecognised relation (incl. 'Self 1', 'Siblings') leaves $relationIds undefined -> ErrorException -> 'Please upload proper excel file' (UNKNOWN exact PHP message). Sheets 2 and 3 in sample are empty.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Relation | relation | relation_id (+count) | string 'Self' (exact, case of first letter ignored) or '<Relation> <count>' e.g. 'Child 2': first token ucfirst'd must be one of Spouse, Partner, Child(=Son+Daughter rows), Son, Daughter, Parent(=Father+Mother), Father, Mother, Parent-in-law(=father/mother in law), Father-in-law, Mother-in-law; token after space => count column. Case-sensitive after ucfirst (e.g. 'Parent-In-Law' fails) | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:186-234 |
| Suminsured | suminsured | relation_sum_insured | number; copied to each expanded relation row | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:186-234 |
| (optional via suffix) count | (second word of Relation) | count | string/number or NULL | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:187-190,225 |

### 22 - Grade + Designation + Age + Construct (GDAC)

- Sample: `public/SampleFormat/gdac_suminsured_80.xlsx` (sample_type_id 80); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Header for 'others' is 'Others' (key others), not 'Per Siblings'. Multi-digit counts unsupported (only first char read, I:700-705). no_of_adult is recomputed inside the token loop (I:742-743). Empty token string would error on $group[0].

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Construct | construct | no_of_adult + no_of_children | as subtype 9 string | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:622-660 |
| Grade | grade | grade_id | string -> addGrade() | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:647-651 |
| Designation | designation | designation_id | string -> addDesignation() | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:654-658 |
| (not in sample) Age Band | age_band | age | same getAgeBand() rules | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:669-674 |
| Employee Age Band | employee_age_band | employee_age | string verbatim | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:676-680 |
| Family Group | family_group | family_group_id | CSV tokens (optional single-digit count prefix, e.g. '2C','2P'): E,S,C,P,PIL,PR,SB (case-insens.); MasterFamilyGroup::firstOrCreate on counts; also stores family_group string and family_relation_ids | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:683-760 |
| Total Cover | total_cover | total_cover | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:625 |
| (not in sample) Claim Rate | claim_rate | is_claim_rate | flag: Yes/Y/yes/y/1 => 1 else 0 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:625-630 |
| (not in sample) Can Add Parent | can_add_parent | can_add_parent | flag: Y/YES (case-insens.) => 1; any other non-null value => 0; column absent/blank => 1 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:93-106,640-641 |
| (not in sample) Can Add Parent In Laws | can_add_parent_in_laws | can_add_parent_in_laws | same as can_add_parent | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:93-106,640-641 |
| Self | self | self_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Spouse | spouse | spouse_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Partner | partner | partner_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Per Kid | per_kid | kids_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Per Parent | per_parent | single_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Per Parent In Law | per_parent_in_law | single_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Set of Parents | set_of_parents | double_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Set of Parent In Laws | set_of_parent_in_laws | double_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |
| Others | others | others_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:762-770 |

### 23 - LOL Grade of Employee

- Sample: `public/SampleFormat/only_grade_si_lol.xlsx` (sample_type_id 83); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Identical to subtype 3 minus can_add_parent flags. Sample has repeating grade names (co-pilot, captain, senior-captian, aviation-head).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Grade | grade | grade_id | string -> addGrade() | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:773-792 |
| Sum Insured | sum_insured | grade_suminsured | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:773-792 |

### 24 - Flat (Fixed SI) [LOL]

- Sample: `None` (sample_type_id None); layout `no-sheet`; header rows 0; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Seeder sample_type_id=NULL; no importer branch (UNKNOWN origin of rates: not found in PCC grep).

### 25 - LOL Age of Employee

- Sample: `public/SampleFormat/age_si_lol.xlsx` (sample_type_id 85); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Same as subtype 2.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Age | age | age | getAgeBand() string | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:794-809 |
| Sum Insured | sum_insured | age_suminsured | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:794-809 |

### 26 - LOL Grade With Construct

- Sample: `public/SampleFormat/grade_with_construct_si_lol.xlsx` (sample_type_id 88); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Same sample layout as subtype 13 (including T&C sheet). can_add_parent not written.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Grade | grade | grade_id | string -> addGrade() | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:811-818 |
| Family Construct | family_construct | no_of_adult + no_of_children | as subtype 9 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:835-838 |
| Is Family Relation Max Capping | is_family_relation_max_capping | is_family_relation_max_capping | 0/1 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:840-842 |
| Family Relation | family_relation | family_relation_id | CSV E,S,C,P,PR,SB,PIL as subtype 13 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:844-853 |
| Total Cover | total_cover | total_cover AND grade_suminsured | number; grade_suminsured is set equal to total_cover (I:869-880) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:820-880 |
| (not in sample) Total SI | total_si | grade_suminsured | number; overrides grade_suminsured set from total_cover when present (I:878-880) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:878-880 |
| (not in sample) Claim Rate | claim_rate | is_claim_rate | flag: Yes/Y/yes/y/1 => 1 else 0 | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:825-828 |
| Self | self | self_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Spouse | spouse | spouse_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Partner | partner | partner_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Per Kid | per_kid | kids_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Per Parent | per_parent | single_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Per Parent In Law | per_parent_in_law | single_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Set of Parents | set_of_parents | double_parent_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| Set of Parent In Laws | set_of_parent_in_laws | double_parent_in_law_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |
| (not in sample) Per Siblings | per_siblings | others_suminsured | number | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:862-871 |

### 27 - LOL Number Of Times Salary

- Sample: `public/SampleFormat/grade_wise_number_of_time_salary_si_lol.xlsx` (sample_type_id 87); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Controller ALSO has a form-array path (salary JSON) for LOL_NUMBER_OF_TIMES_SALARY at PCC:2698; which the UI uses is UNKNOWN.

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Grade | grade | grade_id | string; optional (@ guard) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:884-910 |
| No of Time Salary | no_of_time_salary | no_of_times_of_salary | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:884-910 |
| Min SI Limit | min_si_limit | min_si_limit | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:884-910 |
| Max SI Limit | max_si_limit | max_si_limit | number | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:884-910 |

### 28 - No Of Times Suminsured

- Sample: `None` (sample_type_id None); layout `no-sheet`; header rows 0; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Form array multiple_no_of_time_suminsured[].{suminsured, no_of_time_suminsured[]} -> flat_suminsured + no_of_time_suminsured rows (cover_type=2 if si_type=3) (PCC:2662-2686, 5105-5130). No sheet; seeder sample_type_id=NULL.

### 29 - Based on Base Policy

- Sample: `None` (sample_type_id None); layout `no-sheet`; header rows 0; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Form fields si_calculation_percentage, min_si_limit, max_si_limit, rounding_type, rounding_unit -> master_policy_rates (PCC:2716-2740). No sheet; seeder sample_type_id=NULL.

### 30 - Based on Base SI & Topup Premium

- Sample: `public/SampleFormat/base-on-base-si-and-premium-sample.xlsx` (sample_type_id 91); layout `matrix`; header rows 2; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: TOP-UP matrix parsed by PolicyCustomRateImport (ToCollection, raw grid, no heading slug) (C:50-200); selected when topup policy subtype==30 (C:46-48). Uploaded via topup_si_file (PCC:5343-5380) or si_file/in_suminsured_file for subtype 30 (PCC:2764, 2872). Two header rows (row indices 0 and 1). Row 0: B1='Top-up Factor' (label), C1='Top-up Suminsured' (label, informational). Row 1: A2='Base SI / Deductible' (literal, C:67), B2=top-up factor name (C:61 reads $collection[1][1]), C2.. = numeric top-up SI values (is_numeric test, C:72). Data rows (index>=2): A=base SI, B=factor value, C..=premium for that (base SI, factor value, top-up SI). Zero/blank premium skipped (C:78). Factor name must equal one of 'Age','Grade','Designation','Family Construct','Family Group' (case-sensitive) else no factor column is set (C:97-186). Age value is stored VERBATIM (no getAgeBand) in master_policy_rates.age (C:97-99). Family Construct -> no_of_adult/no_of_children via getConstructCount; Family Group -> MasterFamilyGroup firstOrCreate -> family_group_id (C:110-185).
- tax: addTax(premium, tax) = premium*(1+tax/100) (helper.php:4534); tax% = request premium_tax when premium_tax_type==1 else 0 (PCC:5346-5349). Stored in `tax` column.
- derived: {"total_premium": "premium + premium*tax/100", "is_premium_rate": 1, "is_opd_rate": 0, "cover_type": "FAMILY(2) when si_type==3 via si_file else request-derived", "created_by": "User::first()->id (C:30)"}
- matrix: {"headerRows": 2, "row0": "[blank,'Top-up Factor','Top-up Suminsured']", "row1": "['Base SI / Deductible', <factor>, topUpSI_1, topUpSI_2, ...]", "dataRows": "[baseSI, factorValue, premium_1, premium_2, ...]", "cellMeaning": "pre-tax premium (matrix cell)"}
- writes: ["policy_id(topup)", "base_suminsured", "top_up_suminsured", "age|grade_id|designation_id|no_of_adult+no_of_children|family_group_id", "is_premium_rate=1", "total_premium", "cover_type", "is_opd_rate=0", "tax", "ip_address", "created_by", "created_at", "updated_at"]
- sampleContents: Factor=Age; bands 18-40, 41-60, 61-120 x base SI 200000/300000/500000/1000000 x top-up SI 2000000/3000000.

### 31 - Percentage of Salary

- Sample: `public/SampleFormat/percentage_of_salary_sample.xlsx` (sample_type_id 99); layout `flat-table`; header rows 1; importer `app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php`
- Notes: Age optional so a single row (no age) gives a global percentage; but then min/max limits are dropped (NULL).

| header (sample) | accepted slug key(s) | master_policy_rates column | type / format | required | source |
|---|---|---|---|---|---|
| Age | age | age | getAgeBand() string; optional | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:912-917 |
| Percentage | percentage | percentage | number; header slug 'percentage' required (missing => ErrorException) | yes | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:921 |
| Min Si Limit | min_si_limit | min_si_limit | number; READ ONLY IF Age cell present, else forced NULL (I:918-919) | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:918-919 |
| Max Si Limit | max_si_limit | max_si_limit | number; same rule as min | no | app/Imports/Policy/PolicyConfiguratorSumInsuredImport.php:919-920 |
