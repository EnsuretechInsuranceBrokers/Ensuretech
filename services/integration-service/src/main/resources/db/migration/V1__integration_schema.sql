-- integration-service owns schema `integration` in the shared PostgreSQL.
CREATE TYPE integration.tpa_adapter_enum AS ENUM ('GENERIC_REST');
CREATE TYPE integration.tpa_auth_enum    AS ENUM ('NONE', 'BASIC', 'BEARER');
CREATE TYPE integration.sync_status_enum AS ENUM ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'DEAD', 'SKIPPED');

-- How to reach a broker's TPA. Credentials are never stored: secret_ref names environment variables
-- (<ref>_USERNAME / <ref>_PASSWORD for BASIC, <ref>_TOKEN for BEARER) that the deployment injects from its secret store.
CREATE TABLE integration.tpa_configs (
    id          BIGSERIAL PRIMARY KEY,
    broker_id   BIGINT   NOT NULL,
    tpa_id      SMALLINT NOT NULL,
    adapter     integration.tpa_adapter_enum NOT NULL,
    base_url    VARCHAR(300) NOT NULL,
    auth_type   integration.tpa_auth_enum NOT NULL DEFAULT 'NONE',
    secret_ref  VARCHAR(60),
    batch_size  INTEGER  NOT NULL DEFAULT 100 CHECK (batch_size BETWEEN 1 AND 1000),
    timeout_ms  INTEGER  NOT NULL DEFAULT 5000 CHECK (timeout_ms BETWEEN 100 AND 60000),
    enabled     BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (broker_id, tpa_id),
    CHECK (auth_type = 'NONE' OR secret_ref IS NOT NULL)
);

-- policy -> TPA, learned from PolicyApproved events (the policy-service owns the truth).
CREATE TABLE integration.policy_routes (
    policy_id   BIGINT PRIMARY KEY,
    broker_id   BIGINT NOT NULL,
    employer_id BIGINT NOT NULL,
    tpa_id      SMALLINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per EndorsementApplied event: the sync log and the work queue in one.
CREATE TABLE integration.sync_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            UUID NOT NULL UNIQUE,                 -- at-least-once delivery: a replay finds this row
    endorsement_job_id  UUID NOT NULL,
    broker_id           BIGINT NOT NULL,
    employer_id         BIGINT NOT NULL,
    policy_id           BIGINT NOT NULL,
    tpa_id              SMALLINT,
    op_type             VARCHAR(20) NOT NULL,
    members_expected    INTEGER NOT NULL,
    status              integration.sync_status_enum NOT NULL DEFAULT 'PENDING',
    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until         TIMESTAMPTZ,
    cursor_id           BIGINT NOT NULL DEFAULT 0,            -- last roster id fully handled: retries resume, never resend
    synced_count        INTEGER NOT NULL DEFAULT 0,
    rejected_count      INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);
CREATE INDEX idx_sync_due ON integration.sync_jobs (next_attempt_at) WHERE status IN ('PENDING', 'IN_PROGRESS');
CREATE INDEX idx_sync_policy ON integration.sync_jobs (broker_id, policy_id, created_at DESC);

-- Members the TPA refused, for operators to fix; the sync itself carries on.
CREATE TABLE integration.sync_rejections (
    id           BIGSERIAL PRIMARY KEY,
    sync_job_id  UUID NOT NULL REFERENCES integration.sync_jobs(id),
    member_id    BIGINT NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sync_job_id, member_id)
);
