-- Pro-rata kinds follow the legacy system: DAILY and MONTHLY (NONE = pro-rata not allowed). QUARTERLY / HALF_YEARLY were never implemented there.
ALTER TYPE policy_svc.pro_rata_type RENAME VALUE 'EXACT_DAYS' TO 'DAILY';
ALTER TYPE policy_svc.pro_rata_type RENAME VALUE 'FULL_MONTH' TO 'MONTHLY';

-- Per-relation rules (the legacy `policy_age_details`): age window, minimum age gap to the employee or spouse, own pro-rata kind, max twins.
-- A NULL pro_rata_type inherits the policy's. The policy's NONE switches pro-rata off for every relation.
CREATE TABLE policy_svc.relation_rules (
    id                       BIGSERIAL PRIMARY KEY,
    policy_id                BIGINT      NOT NULL REFERENCES policy_svc.policies(id),
    broker_id                BIGINT      NOT NULL,
    relation_code            VARCHAR(40) NOT NULL,
    min_age                  SMALLINT    CHECK (min_age BETWEEN 0 AND 120),
    max_age                  SMALLINT    CHECK (max_age BETWEEN 0 AND 120),
    age_difference           SMALLINT    CHECK (age_difference BETWEEN 0 AND 120),
    difference_from_relation VARCHAR(10) CHECK (difference_from_relation IN ('SELF', 'SPOUSE')),
    pro_rata_type            policy_svc.pro_rata_type,
    max_twins                SMALLINT    NOT NULL DEFAULT 0 CHECK (max_twins BETWEEN 0 AND 5),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_id, relation_code),
    CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age),
    CHECK ((age_difference IS NULL) = (difference_from_relation IS NULL))
);
