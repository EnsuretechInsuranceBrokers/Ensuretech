-- Operator controls over background jobs: a hashed "job killer" password, and an audit trail of every attempt to use it.
ALTER TYPE integration.sync_status_enum ADD VALUE IF NOT EXISTS 'CANCELLED';

-- One row: the bcrypt hash of the job-killer password (never the password), and the lockout state that protects it from guessing.
CREATE TABLE integration.job_control_credentials (
    id              smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    password_hash   text        NOT NULL,
    set_at          timestamptz NOT NULL DEFAULT now(),
    set_by          text,
    failed_attempts int         NOT NULL DEFAULT 0,
    locked_until    timestamptz
);

CREATE TABLE integration.job_control_audit (
    id          bigserial PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    action      text        NOT NULL,          -- KILL | REQUEUE | PASSWORD_CHANGE | BOOTSTRAP
    job_id      uuid,
    actor       text,                          -- the service account that made the call
    remote_addr text,
    outcome     text        NOT NULL,          -- OK | WRONG_PASSWORD | LOCKED | NOT_FOUND | NOT_ALLOWED
    detail      text
);
CREATE INDEX idx_job_control_audit_at ON integration.job_control_audit (at DESC);
