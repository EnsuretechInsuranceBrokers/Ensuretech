-- Who pays what share of a member's premium (legacy policy.premium_contribution_type + the per-relation *_employee_contribution columns).
--   PERCENT: the employee pays that % of the member's premium, the employer the rest.
--   FIXED:   the employee pays that amount per member (never more than the premium), the employer the rest.
-- NONE (no row, or type NONE) = the split is not computed; the engine reports it as unknown rather than guessing who pays.
CREATE TABLE policy_svc.contribution_plans (
    policy_id         BIGINT      PRIMARY KEY REFERENCES policy_svc.policies(id) ON DELETE CASCADE,
    broker_id         BIGINT      NOT NULL,
    contribution_type VARCHAR(10) NOT NULL CHECK (contribution_type IN ('NONE', 'PERCENT', 'FIXED')),
    -- member group -> employee contribution, e.g. {"SELF": 0, "SPOUSE": 50, "CHILD": 50, "PARENT_SINGLE": 100}
    groups            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
