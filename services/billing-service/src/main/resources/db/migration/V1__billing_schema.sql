-- billing-service owns the `billing` schema: one contingency-deposit (CD) account per policy, an append-only ledger,
-- per-job reservations, and the replenishment workflow.
CREATE TYPE billing.entry_type AS ENUM ('CREDIT', 'DEBIT');
CREATE TYPE billing.reference_type AS ENUM ('DEPOSIT', 'REPLENISHMENT', 'ENDORSEMENT', 'ADJUSTMENT');
CREATE TYPE billing.reservation_status AS ENUM ('HELD', 'COMMITTED', 'RELEASED');
CREATE TYPE billing.replenishment_status AS ENUM ('REQUESTED', 'APPROVED', 'REJECTED');

CREATE TABLE billing.cd_accounts (
    id               BIGSERIAL PRIMARY KEY,
    broker_id        BIGINT        NOT NULL,
    employer_id      BIGINT        NOT NULL,
    policy_id        BIGINT        NOT NULL UNIQUE,
    balance          NUMERIC(18,2) NOT NULL DEFAULT 0,          -- may go negative only via a forced debit (deficit)
    reserved         NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    threshold_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (threshold_amount >= 0),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_cd_accounts_broker ON billing.cd_accounts(broker_id);
CREATE INDEX idx_cd_accounts_employer ON billing.cd_accounts(broker_id, employer_id);

-- Every balance change is a ledger row. The unique reference is the idempotency key: replaying a payment, a
-- replenishment approval or an endorsement event can never move money twice.
CREATE TABLE billing.cd_ledger (
    id             BIGSERIAL PRIMARY KEY,
    account_id     BIGINT                 NOT NULL REFERENCES billing.cd_accounts(id),
    entry_type     billing.entry_type     NOT NULL,
    amount         NUMERIC(18,2)          NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(18,2)          NOT NULL,
    reference_type billing.reference_type NOT NULL,
    reference_id   VARCHAR(80)            NOT NULL,
    narration      TEXT,
    created_by     VARCHAR(80)            NOT NULL,
    created_at     TIMESTAMPTZ            NOT NULL DEFAULT now(),
    UNIQUE (account_id, reference_type, reference_id)
);
CREATE INDEX idx_cd_ledger_account_time ON billing.cd_ledger(account_id, id DESC);

CREATE FUNCTION billing.ledger_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'cd_ledger is append-only (% blocked)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER cd_ledger_no_change BEFORE UPDATE OR DELETE ON billing.cd_ledger
    FOR EACH ROW EXECUTE FUNCTION billing.ledger_immutable();

-- One reservation per endorsement job: money set aside between "the check passed" and "the endorsement applied".
CREATE TABLE billing.cd_reservations (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT                    NOT NULL REFERENCES billing.cd_accounts(id),
    job_id      UUID                      NOT NULL UNIQUE,
    amount      NUMERIC(18,2)             NOT NULL CHECK (amount > 0),
    status      billing.reservation_status NOT NULL DEFAULT 'HELD',
    expires_at  TIMESTAMPTZ               NOT NULL,
    created_at  TIMESTAMPTZ               NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_cd_reservations_expiry ON billing.cd_reservations(expires_at) WHERE status = 'HELD';

CREATE TABLE billing.cd_replenishment_requests (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT                       NOT NULL REFERENCES billing.cd_accounts(id),
    amount       NUMERIC(18,2)                NOT NULL CHECK (amount > 0),
    reference    VARCHAR(80)                  NOT NULL,        -- the payment reference (UTR / cheque no.)
    status       billing.replenishment_status NOT NULL DEFAULT 'REQUESTED',
    requested_by VARCHAR(80)                  NOT NULL,
    decided_by   VARCHAR(80),
    decision_note TEXT,
    created_at   TIMESTAMPTZ                  NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ,
    UNIQUE (account_id, reference)                              -- one payment reference is credited at most once
);

-- Consumer idempotency: an event id is processed at most once (Kafka delivery is at-least-once).
CREATE TABLE billing.processed_events (
    event_id    UUID PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
