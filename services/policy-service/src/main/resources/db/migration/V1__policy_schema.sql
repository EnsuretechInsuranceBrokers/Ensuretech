-- policy-service owns the `policy_svc` schema. Lookup tables are seeded from the legacy constant.php design.
CREATE TYPE policy_svc.policy_status AS ENUM ('APPROVED_AND_LIVE', 'NOT_APPROVED', 'INACTIVE', 'EXPIRED');
CREATE TYPE policy_svc.pro_rata_type AS ENUM ('FULL_MONTH', 'EXACT_DAYS', 'NONE');

CREATE TABLE policy_svc.premium_types (
    id               SMALLINT PRIMARY KEY,
    code             VARCHAR(60)  NOT NULL UNIQUE,
    name             VARCHAR(120) NOT NULL,
    rater_class      VARCHAR(60)  NOT NULL,
    is_age_banded    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_grade_based   BOOLEAN      NOT NULL DEFAULT FALSE,
    supports_topup   BOOLEAN      NOT NULL DEFAULT FALSE,
    construct_raters TEXT[]       NOT NULL DEFAULT '{}',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE policy_svc.suminsured_subtypes (
    id              SMALLINT PRIMARY KEY,
    code            VARCHAR(60)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    allows_grade    BOOLEAN      NOT NULL DEFAULT FALSE,
    allows_age_band BOOLEAN      NOT NULL DEFAULT FALSE,
    grade_raters    TEXT[]       NOT NULL DEFAULT '{}',
    age_raters      TEXT[]       NOT NULL DEFAULT '{}'
);

CREATE TABLE policy_svc.rater_types (
    id           SMALLINT PRIMARY KEY,
    code         VARCHAR(40) NOT NULL UNIQUE,
    name         VARCHAR(80) NOT NULL,
    supports_opd BOOLEAN     NOT NULL DEFAULT FALSE,
    supports_ipd BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE policy_svc.relation_types (
    id               SMALLINT PRIMARY KEY,
    code             VARCHAR(40) NOT NULL UNIQUE,
    name             VARCHAR(60) NOT NULL,
    relation_group   VARCHAR(20) NOT NULL,
    is_dependent     BOOLEAN     NOT NULL DEFAULT TRUE,
    max_age          SMALLINT,
    sibling_sequence SMALLINT,
    tpa_label_map    JSONB       NOT NULL DEFAULT '{}'::jsonb
);

INSERT INTO policy_svc.premium_types (id, code, name, rater_class, is_age_banded, is_grade_based, supports_topup, construct_raters) VALUES
(1,  'FLAT',                     'Flat Premium',                         'FlatRater',        FALSE, FALSE, FALSE, '{}'),
(2,  'AGE_BAND',                 'Age Band Premium',                     'AgeRater',         TRUE,  FALSE, FALSE, '{AGE_BAND}'),
(3,  'GRADE',                    'Grade-Based Premium',                  'GradeRater',       FALSE, TRUE,  FALSE, '{GRADE,DESIGNATION}'),
(4,  'GDAC',                     'GDAC Premium',                         'GDACRater',        TRUE,  FALSE, FALSE, '{GDAC}'),
(5,  'LOL',                      'Limit of Liability Premium',           'LOLRater',         FALSE, FALSE, FALSE, '{LOL}'),
(6,  'CONSTRUCT',                'Construct-Based Premium',              'ConstructRater',   FALSE, FALSE, FALSE, '{GRADE,DESIGNATION,AGE_BAND}'),
(7,  'PERMILLY',                 'Per Mille Premium',                    'PermillyRater',    FALSE, FALSE, FALSE, '{}'),
(8,  'FLAT_TOPUP',               'Flat with Top-Up',                     'FlatRater',        FALSE, FALSE, TRUE,  '{}'),
(9,  'AGE_BAND_TOPUP',           'Age Band with Top-Up',                 'AgeRater',         TRUE,  FALSE, TRUE,  '{AGE_BAND}'),
(10, 'GRADE_TOPUP',              'Grade with Top-Up',                    'GradeRater',       FALSE, TRUE,  TRUE,  '{GRADE}'),
(11, 'GDAC_TOPUP',               'GDAC with Top-Up',                     'GDACRater',        TRUE,  FALSE, TRUE,  '{GDAC}'),
(12, 'FAMILY_FLOATER',           'Family Floater',                       'FlatRater',        FALSE, FALSE, FALSE, '{FAMILY}'),
(13, 'FAMILY_FLOATER_AGE',       'Family Floater Age-Based',             'AgeRater',         TRUE,  FALSE, FALSE, '{FAMILY}'),
(14, 'FAMILY_FLOATER_GRADE',     'Family Floater Grade-Based',           'GradeRater',       FALSE, TRUE,  FALSE, '{FAMILY,GRADE}'),
(15, 'INDIVIDUAL_FLOATER',       'Individual Floater',                   'FlatRater',        FALSE, FALSE, FALSE, '{}'),
(16, 'INDIVIDUAL_AGE',           'Individual Age-Banded',                'AgeRater',         TRUE,  FALSE, FALSE, '{AGE_BAND}'),
(17, 'MULTI_YEAR',               'Multi-Year Flat',                      'FlatRater',        FALSE, FALSE, FALSE, '{}'),
(18, 'PRO_RATA_FLAT',            'Pro-Rata Flat',                        'ProRataRater',     FALSE, FALSE, FALSE, '{}'),
(19, 'PRO_RATA_AGE',             'Pro-Rata Age-Banded',                  'ProRataAgeRater',  TRUE,  FALSE, FALSE, '{AGE_BAND}'),
(20, 'BASE_ENHANCE_FLEX',        'Base + Enhance + Flex',                'FlexRater',        FALSE, FALSE, FALSE, '{FLEX}'),
(21, 'BASE_PLUS_TOPUP',          'Base + Top-Up',                        'BaseTopUpRater',   FALSE, FALSE, TRUE,  '{}'),
(22, 'GRADE_AGE_BAND',           'Grade × Age Band Matrix',              'MatrixRater',      TRUE,  TRUE,  FALSE, '{GRADE,AGE_BAND}'),
(23, 'DESIGNATION',              'Designation-Based',                    'DesignationRater', FALSE, FALSE, FALSE, '{DESIGNATION}'),
(24, 'DESIGNATION_AGE',          'Designation × Age',                    'MatrixRater',      TRUE,  FALSE, FALSE, '{DESIGNATION,AGE_BAND}'),
(25, 'COMPOSITE',                'Composite Multi-Section',              'CompositeRater',   FALSE, FALSE, FALSE, '{GRADE,DESIGNATION,AGE_BAND}'),
(26, 'FLAT_EMPLOYEE_ONLY',       'Flat Employee Only',                   'FlatRater',        FALSE, FALSE, FALSE, '{}'),
(27, 'FLAT_EMPLOYEE_PLUS_1',     'Flat Employee + 1 Dependent',          'FlatRater',        FALSE, FALSE, FALSE, '{}'),
(28, 'FLAT_EMPLOYEE_PLUS_2',     'Flat Employee + 2 Dependents',         'FlatRater',        FALSE, FALSE, FALSE, '{}'),
(29, 'FLAT_FAMILY_DEFINITION',   'Flat by Family Definition',            'FlatRater',        FALSE, FALSE, FALSE, '{FAMILY}'),
(30, 'OPD_FLAT',                 'OPD Flat Benefit',                     'OpdFlatRater',     FALSE, FALSE, FALSE, '{}'),
(31, 'OPD_GRADE',                'OPD Grade-Based',                      'OpdGradeRater',    FALSE, TRUE,  FALSE, '{GRADE}'),
(32, 'WELLNESS_BUNDLE',          'Wellness + GMC Bundle',                'BundleRater',      FALSE, FALSE, FALSE, '{}');

INSERT INTO policy_svc.suminsured_subtypes (id, code, name, allows_grade, allows_age_band, grade_raters, age_raters) VALUES
(1,  'FIXED',                    'Fixed Sum Insured',                FALSE, FALSE, '{}', '{}'),
(2,  'GRADE_WISE',               'Grade-Wise Sum Insured',           TRUE,  FALSE, '{GRADE,DESIGNATION}', '{}'),
(3,  'AGE_BAND_WISE',            'Age Band Sum Insured',             FALSE, TRUE,  '{}', '{AGE_BAND}'),
(4,  'GRADE_AGE_MATRIX',         'Grade × Age Matrix',               TRUE,  TRUE,  '{GRADE}', '{AGE_BAND}'),
(5,  'SALARY_MULTIPLE',          'Multiple of Annual Salary',        FALSE, FALSE, '{}', '{}'),
(6,  'SALARY_MULTIPLE_GRADE',    'Salary Multiple by Grade',         TRUE,  FALSE, '{GRADE}', '{}'),
(7,  'FAMILY_FLOATER',           'Family Floater SI',                FALSE, FALSE, '{}', '{}'),
(8,  'FAMILY_FLOATER_GRADE',     'Family Floater by Grade',          TRUE,  FALSE, '{GRADE}', '{}'),
(9,  'INDIVIDUAL_SI',            'Individual Sum Insured',           FALSE, FALSE, '{}', '{}'),
(10, 'RESTORE',                  'Restore / Reinstate Benefit',      FALSE, FALSE, '{}', '{}'),
(11, 'RESTORE_GRADE',            'Restore by Grade',                 TRUE,  FALSE, '{GRADE}', '{}'),
(12, 'LOL_SI',                   'Limit of Liability SI',            FALSE, FALSE, '{}', '{}'),
(13, 'TOP_UP_FIXED',             'Fixed Top-Up SI',                  FALSE, FALSE, '{}', '{}'),
(14, 'TOP_UP_GRADE',             'Grade-Wise Top-Up',                TRUE,  FALSE, '{GRADE}', '{}'),
(15, 'TOP_UP_AGE',               'Age-Band Top-Up',                  FALSE, TRUE,  '{}', '{AGE_BAND}'),
(16, 'SUPER_TOP_UP',             'Super Top-Up SI',                  FALSE, FALSE, '{}', '{}'),
(17, 'MULTI_YEAR_FIXED',         'Multi-Year Fixed SI',              FALSE, FALSE, '{}', '{}'),
(18, 'OPD_FIXED',                'Fixed OPD Benefit',                FALSE, FALSE, '{}', '{}'),
(19, 'OPD_GRADE',                'Grade-Wise OPD Benefit',           TRUE,  FALSE, '{GRADE}', '{}'),
(20, 'COMPOSITE_SECTIONS',       'Composite Sections SI',            FALSE, FALSE, '{}', '{}'),
(21, 'DESIGNATION_WISE',         'Designation-Wise SI',              TRUE,  FALSE, '{DESIGNATION}', '{}'),
(22, 'FLEX_BENEFIT',             'Flex Benefit SI',                  FALSE, FALSE, '{}', '{}'),
(23, 'GPA_SI',                   'GPA Sum Insured',                  FALSE, FALSE, '{}', '{}'),
(24, 'GPA_GRADE',                'GPA Grade-Wise',                   TRUE,  FALSE, '{GRADE}', '{}'),
(25, 'GPA_SALARY',               'GPA Salary Multiple',              FALSE, FALSE, '{}', '{}'),
(26, 'GTL_SI',                   'GTL Sum Insured',                  FALSE, FALSE, '{}', '{}'),
(27, 'GTL_SALARY',               'GTL Salary Multiple',              FALSE, FALSE, '{}', '{}'),
(28, 'WELLNESS_WALLET',          'Wellness Wallet Benefit',          FALSE, FALSE, '{}', '{}'),
(29, 'PARENTAL_FLOATER',         'Parental Floater SI',              FALSE, FALSE, '{}', '{}'),
(30, 'PARENTAL_GRADE',           'Parental Floater by Grade',        TRUE,  FALSE, '{GRADE}', '{}'),
(31, 'EMPLOYEE_PLUS_PARENTS',    'Employee + Parents SI',            FALSE, FALSE, '{}', '{}');

INSERT INTO policy_svc.rater_types (id, code, name, supports_opd, supports_ipd) VALUES
(1, 'IPD',              'IPD Only',                     FALSE, TRUE),
(2, 'OPD',              'OPD Only',                     TRUE,  FALSE),
(3, 'IPD_PLUS_OPD',     'IPD + OPD Combined',           TRUE,  TRUE),
(4, 'BASE_PLUS_TOP_UP', 'Base + Top-Up',                FALSE, TRUE),
(5, 'BASE_ENHANCE_FLEX','Base + Enhance + Flex Layers', FALSE, TRUE);

INSERT INTO policy_svc.relation_types (id, code, name, relation_group, is_dependent, max_age, sibling_sequence, tpa_label_map) VALUES
(1,  'SELF',            'Self / Employee',      'SELF',             FALSE, NULL, NULL, '{"CARE":"SELF","MEDIASSIST":"Self","FHPL":"EMP"}'),
(2,  'SPOUSE',          'Spouse',               'SPOUSE',           TRUE,  NULL, NULL, '{"CARE":"SPOUSE","MEDIASSIST":"Spouse","FHPL":"SP"}'),
(3,  'HUSBAND',         'Husband',              'SPOUSE',           TRUE,  NULL, NULL, '{"CARE":"SPOUSE","MEDIASSIST":"Spouse","FHPL":"SP"}'),
(4,  'WIFE',            'Wife',                 'SPOUSE',           TRUE,  NULL, NULL, '{"CARE":"SPOUSE","MEDIASSIST":"Spouse","FHPL":"SP"}'),
(5,  'SON',             'Son',                  'CHILD',            TRUE,  25,   1,    '{"CARE":"SON","MEDIASSIST":"Son","FHPL":"S"}'),
(6,  'SON_II',          'Son (2nd)',             'CHILD',            TRUE,  25,   2,    '{"CARE":"SON","MEDIASSIST":"Son","FHPL":"S"}'),
(7,  'SON_III',         'Son (3rd)',             'CHILD',            TRUE,  25,   3,    '{"CARE":"SON","MEDIASSIST":"Son","FHPL":"S"}'),
(8,  'SON_IV',          'Son (4th)',             'CHILD',            TRUE,  25,   4,    '{"CARE":"SON","MEDIASSIST":"Son","FHPL":"S"}'),
(9,  'DAUGHTER',        'Daughter',             'CHILD',            TRUE,  25,   1,    '{"CARE":"DAUGHTER","MEDIASSIST":"Daughter","FHPL":"D"}'),
(10, 'DAUGHTER_II',     'Daughter (2nd)',        'CHILD',            TRUE,  25,   2,    '{"CARE":"DAUGHTER","MEDIASSIST":"Daughter","FHPL":"D"}'),
(11, 'DAUGHTER_III',    'Daughter (3rd)',        'CHILD',            TRUE,  25,   3,    '{"CARE":"DAUGHTER","MEDIASSIST":"Daughter","FHPL":"D"}'),
(12, 'FATHER',          'Father',               'PARENT',           TRUE,  NULL, NULL, '{"CARE":"FATHER","MEDIASSIST":"Father","FHPL":"F"}'),
(13, 'MOTHER',          'Mother',               'PARENT',           TRUE,  NULL, NULL, '{"CARE":"MOTHER","MEDIASSIST":"Mother","FHPL":"M"}'),
(14, 'FATHER_IN_LAW',   'Father-in-Law',        'PARENT_IN_LAW',    TRUE,  NULL, NULL, '{"CARE":"FATHER-IN-LAW","MEDIASSIST":"Father In Law","FHPL":"FIL"}'),
(15, 'MOTHER_IN_LAW',   'Mother-in-Law',        'PARENT_IN_LAW',    TRUE,  NULL, NULL, '{"CARE":"MOTHER-IN-LAW","MEDIASSIST":"Mother In Law","FHPL":"MIL"}'),
(16, 'BROTHER',         'Brother',              'SIBLING',          TRUE,  NULL, NULL, '{"CARE":"BROTHER","MEDIASSIST":"Brother","FHPL":"BR"}'),
(17, 'SISTER',          'Sister',               'SIBLING',          TRUE,  NULL, NULL, '{"CARE":"SISTER","MEDIASSIST":"Sister","FHPL":"SI"}'),
(18, 'GRANDFATHER',     'Grandfather',          'PARENT',           TRUE,  NULL, NULL, '{"CARE":"GRANDFATHER","MEDIASSIST":"Grand Father","FHPL":"GF"}'),
(19, 'GRANDMOTHER',     'Grandmother',          'PARENT',           TRUE,  NULL, NULL, '{"CARE":"GRANDMOTHER","MEDIASSIST":"Grand Mother","FHPL":"GM"}'),
(20, 'DOMESTIC_PARTNER','Domestic Partner',     'SPOUSE',           TRUE,  NULL, NULL, '{"CARE":"DOMESTIC_PARTNER","MEDIASSIST":"Domestic Partner","FHPL":"DP"}'),
(21, 'ADOPTED_CHILD',   'Adopted Child',        'CHILD',            TRUE,  25,   NULL, '{"CARE":"ADOPTED","MEDIASSIST":"Adopted Child","FHPL":"AC"}'),
(22, 'STEP_CHILD',      'Step Child',           'CHILD',            TRUE,  25,   NULL, '{"CARE":"STEP_CHILD","MEDIASSIST":"Step Child","FHPL":"SC"}'),
(23, 'STEP_FATHER',     'Step Father',          'PARENT',           TRUE,  NULL, NULL, '{"CARE":"STEP_FATHER","MEDIASSIST":"Step Father","FHPL":"SF"}'),
(24, 'STEP_MOTHER',     'Step Mother',          'PARENT',           TRUE,  NULL, NULL, '{"CARE":"STEP_MOTHER","MEDIASSIST":"Step Mother","FHPL":"SM"}'),
(25, 'DEPENDENT',       'Generic Dependent',    'CHILD',            TRUE,  NULL, NULL, '{"CARE":"DEPENDENT","MEDIASSIST":"Dependent","FHPL":"DEP"}');

-- Local projection of organization-service employers (fed from employer.events).
-- Lets us reject a policy pointing at another broker's employer without a cross-service call or FK.
CREATE TABLE policy_svc.employer_refs (
    employer_id BIGINT PRIMARY KEY,
    broker_id   BIGINT       NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_employer_refs_broker ON policy_svc.employer_refs(broker_id);

CREATE TABLE policy_svc.policies (
    id                    BIGSERIAL PRIMARY KEY,
    broker_id             BIGINT       NOT NULL,
    employer_id           BIGINT       NOT NULL,
    policy_number         VARCHAR(60)  NOT NULL,
    insurer_id            SMALLINT     NOT NULL,
    tpa_id                SMALLINT,
    policy_type           VARCHAR(40)  NOT NULL CHECK (policy_type IN ('GMC','GPA','GTL','OPD','WELLNESS')),
    policy_sub_type       VARCHAR(60),
    premium_type_id       SMALLINT     NOT NULL REFERENCES policy_svc.premium_types(id),
    suminsured_subtype_id SMALLINT     NOT NULL REFERENCES policy_svc.suminsured_subtypes(id),
    rater_type_id         SMALLINT     NOT NULL REFERENCES policy_svc.rater_types(id),
    status                policy_svc.policy_status   NOT NULL DEFAULT 'NOT_APPROVED',
    start_date            DATE         NOT NULL,
    end_date              DATE         NOT NULL,
    renewal_date          DATE,
    max_members           INTEGER,
    is_inception          BOOLEAN      NOT NULL DEFAULT FALSE,
    pro_rata_type         policy_svc.pro_rata_type   NOT NULL DEFAULT 'NONE',
    construct_config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_test_data          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (broker_id, policy_number),
    CHECK (end_date > start_date)
);
CREATE INDEX idx_policies_broker_status ON policy_svc.policies(broker_id, status) WHERE is_test_data = FALSE;
CREATE INDEX idx_policies_employer      ON policy_svc.policies(employer_id, status) WHERE is_test_data = FALSE;
CREATE INDEX idx_policies_expiry        ON policy_svc.policies(end_date) WHERE is_test_data = FALSE AND status = 'APPROVED_AND_LIVE';

CREATE TABLE policy_svc.rate_cards (
    id             BIGSERIAL PRIMARY KEY,
    policy_id      BIGINT        NOT NULL REFERENCES policy_svc.policies(id),
    broker_id      BIGINT        NOT NULL,
    effective_from DATE          NOT NULL,
    effective_to   DATE,
    tax_rate       NUMERIC(5,2)  NOT NULL DEFAULT 18.00,
    rate_table     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    is_active      BOOLEAN       NOT NULL DEFAULT TRUE,
    is_test_data   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_rate_cards_one_active ON policy_svc.rate_cards(policy_id)
    WHERE is_active = TRUE AND is_test_data = FALSE;

CREATE TABLE policy_svc.family_constructs (
    id                     BIGSERIAL PRIMARY KEY,
    policy_id              BIGINT      NOT NULL UNIQUE REFERENCES policy_svc.policies(id),
    broker_id              BIGINT      NOT NULL,
    allowed_relations      INTEGER[]   NOT NULL DEFAULT '{}',   -- relation_types ids
    max_members            SMALLINT    NOT NULL DEFAULT 6,
    max_children           SMALLINT,
    max_parents            SMALLINT,
    include_parents        BOOLEAN     NOT NULL DEFAULT FALSE,
    include_parents_in_law BOOLEAN     NOT NULL DEFAULT FALSE,
    age_min                SMALLINT,
    age_max                SMALLINT,
    is_test_data           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
