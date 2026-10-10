-- One monotonically increasing counter per (broker, prefix). Employee codes and e-mail addresses derive from it, so two testers,
-- two browsers or two runs can never be handed the same value (the old browser tool kept this in localStorage).
CREATE TABLE sheetgen.code_counters (
    broker_id  BIGINT       NOT NULL,
    prefix     VARCHAR(20)  NOT NULL,
    next_value BIGINT       NOT NULL CHECK (next_value >= 1),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (broker_id, prefix)
);
