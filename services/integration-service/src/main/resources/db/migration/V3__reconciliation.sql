-- Pull-and-reconcile: ask the TPA which members it covers under a policy and attach the TPA's own member id to ours.
-- Per (broker, TPA) quirks: how the TPA labels relations, and whether its employee codes drop leading zeros (Care does).
CREATE TABLE integration.tpa_reconcile_settings (
    broker_id           BIGINT   NOT NULL,
    tpa_id              SMALLINT NOT NULL,
    strip_leading_zeros BOOLEAN  NOT NULL DEFAULT FALSE,
    PRIMARY KEY (broker_id, tpa_id)
);

CREATE TABLE integration.tpa_relation_mappings (
    broker_id     BIGINT      NOT NULL,
    tpa_id        SMALLINT    NOT NULL,
    tpa_label     VARCHAR(60) NOT NULL,        -- as the TPA writes it, lower-cased and trimmed
    relation_code VARCHAR(40) NOT NULL,        -- our relation code
    PRIMARY KEY (broker_id, tpa_id, tpa_label)
);

CREATE TABLE integration.reconcile_runs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    broker_id        BIGINT      NOT NULL,
    policy_id        BIGINT      NOT NULL,
    tpa_id           SMALLINT    NOT NULL,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('OK', 'FAILED')),
    roster_size      INTEGER     NOT NULL DEFAULT 0,
    tpa_records      INTEGER     NOT NULL DEFAULT 0,
    matched          INTEGER     NOT NULL DEFAULT 0,
    misses           INTEGER     NOT NULL DEFAULT 0,        -- TPA records we could not place
    roster_unmatched INTEGER     NOT NULL DEFAULT 0,        -- our members the TPA did not mention
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconcile_runs_policy ON integration.reconcile_runs (broker_id, policy_id, created_at DESC);

-- The "failed dump" of the legacy system: what could not be placed, for an operator to fix.
CREATE TABLE integration.reconcile_misses (
    run_id        UUID    NOT NULL REFERENCES integration.reconcile_runs(id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,
    employee_code VARCHAR(80),
    relation      VARCHAR(80),
    tpa_member_id VARCHAR(80),
    reason        VARCHAR(300) NOT NULL,
    PRIMARY KEY (run_id, seq)
);
