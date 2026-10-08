-- Legacy CDAccountType: one balance can fund one policy (POLICY), several policies of one employer (GROUP), every policy of an
-- employer (COMPANY, members join automatically) or any policies of the broker (MULTIPLE). A policy is funded by exactly one account,
-- recorded in cd_account_policies; cd_accounts.policy_id remains only as the home policy of a POLICY account.
ALTER TABLE billing.cd_accounts
    ADD COLUMN account_type TEXT NOT NULL DEFAULT 'POLICY' CHECK (account_type IN ('POLICY', 'GROUP', 'COMPANY', 'MULTIPLE')),
    ADD COLUMN name         VARCHAR(120),
    ALTER COLUMN policy_id DROP NOT NULL,
    ALTER COLUMN employer_id DROP NOT NULL;

ALTER TABLE billing.cd_accounts ADD CONSTRAINT cd_accounts_shape CHECK (
    (account_type = 'POLICY') = (policy_id IS NOT NULL)
    AND (account_type <> 'MULTIPLE') = (employer_id IS NOT NULL));

CREATE UNIQUE INDEX uq_cd_company_pool ON billing.cd_accounts (broker_id, employer_id) WHERE account_type = 'COMPANY';

CREATE TABLE billing.cd_account_policies (
    policy_id  BIGINT PRIMARY KEY,                                   -- a policy is funded by exactly one account
    account_id BIGINT      NOT NULL REFERENCES billing.cd_accounts(id) ON DELETE CASCADE,
    broker_id  BIGINT      NOT NULL,
    linked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cd_account_policies_account ON billing.cd_account_policies(account_id);

INSERT INTO billing.cd_account_policies (policy_id, account_id, broker_id)
SELECT policy_id, id, broker_id FROM billing.cd_accounts WHERE policy_id IS NOT NULL;
