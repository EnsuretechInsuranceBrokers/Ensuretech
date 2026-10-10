-- organization-service owns the `organization` schema. The outbox is shared by all services.
CREATE SCHEMA IF NOT EXISTS outbox;

CREATE TABLE organization.brokers (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(120) NOT NULL,
    legal_name       VARCHAR(200),
    gstin            VARCHAR(15),
    pan              VARCHAR(10),
    irdai_licence_no VARCHAR(40),
    contact_email    VARCHAR(255) NOT NULL,
    contact_phone    VARCHAR(20),
    portal_legacy_id SMALLINT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_test_data     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE organization.broker_branches (
    id             BIGSERIAL PRIMARY KEY,
    broker_id      BIGINT      NOT NULL REFERENCES organization.brokers(id),
    branch_code    VARCHAR(20) NOT NULL,
    city           VARCHAR(80),
    state          VARCHAR(80),
    is_head_office BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    is_test_data   BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE (broker_id, branch_code)
);
CREATE INDEX idx_branches_broker ON organization.broker_branches(broker_id) WHERE is_test_data = FALSE;

CREATE TABLE organization.employers (
    id             BIGSERIAL PRIMARY KEY,
    broker_id      BIGINT       NOT NULL REFERENCES organization.brokers(id),
    employer_code  VARCHAR(40)  NOT NULL,
    legal_name     VARCHAR(200) NOT NULL,
    display_name   VARCHAR(120) NOT NULL,
    gstin          VARCHAR(15),
    pan            VARCHAR(10),
    sector         VARCHAR(80),
    employee_count INTEGER,
    contact_email  VARCHAR(255),
    contact_phone  VARCHAR(20),
    hrms_type      VARCHAR(40),
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_hidden      BOOLEAN      NOT NULL DEFAULT FALSE,   -- replaces legacy HiddenEmployers static array
    is_test_data   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (broker_id, employer_code)
);
CREATE INDEX idx_employers_broker ON organization.employers(broker_id, is_active) WHERE is_test_data = FALSE;

-- Replaces hardcoded Portal::check() branches and EmployerConstant id lists.
CREATE TABLE organization.employer_configs (
    id           BIGSERIAL PRIMARY KEY,
    broker_id    BIGINT       NOT NULL REFERENCES organization.brokers(id),
    employer_id  BIGINT       NOT NULL REFERENCES organization.employers(id),
    config_key   VARCHAR(80)  NOT NULL,
    config_value TEXT         NOT NULL,
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (employer_id, config_key)
);

-- Transactional outbox (shared shape with the Django side; see schema_part1.sql)
CREATE TABLE IF NOT EXISTS outbox.outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(60)  NOT NULL,
    aggregate_id   VARCHAR(60)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    payload        JSONB        NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    partition_key  VARCHAR(80),
    status         VARCHAR(12)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','FAILED','DEAD')),
    retry_count    SMALLINT     NOT NULL DEFAULT 0,
    max_retries    SMALLINT     NOT NULL DEFAULT 5,
    published_at   TIMESTAMPTZ,
    next_retry_at  TIMESTAMPTZ,
    last_error     TEXT,
    is_test_data   BOOLEAN      NOT NULL DEFAULT FALSE,
    correlation_id VARCHAR(80),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox.outbox_events(created_at)
    WHERE status IN ('PENDING','FAILED') AND is_test_data = FALSE;
