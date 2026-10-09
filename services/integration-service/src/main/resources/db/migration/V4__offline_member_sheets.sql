-- Offline member-id sheets: when a TPA's server is down (or it simply works in Excel), the TPA uploads, or the broker uploads on the TPA's behalf,
-- the sheet of member ids and e-cards, instead of waiting for the nightly pull. One row per upload and one per sheet line, kept as the audit trail.
CREATE TABLE integration.offline_uploads (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    broker_id        BIGINT       NOT NULL,
    policy_id        BIGINT       NOT NULL,
    tpa_id           SMALLINT     NOT NULL,
    source           VARCHAR(20)  NOT NULL CHECK (source IN ('TPA_PORTAL', 'BROKER_FOR_TPA')),   -- who put the sheet in: the TPA itself, or the broker for it
    uploaded_by      VARCHAR(64)  NOT NULL,
    uploaded_by_role VARCHAR(30)  NOT NULL,
    filename         VARCHAR(255) NOT NULL,
    sha256           CHAR(64)     NOT NULL,
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('APPLIED', 'FAILED')),
    rows_total       INTEGER      NOT NULL DEFAULT 0,
    assigned         INTEGER      NOT NULL DEFAULT 0,     -- members that got a TPA member id they did not have
    reassigned       INTEGER      NOT NULL DEFAULT 0,     -- members whose TPA member id was replaced by a different one
    ecards_added     INTEGER      NOT NULL DEFAULT 0,
    unchanged        INTEGER      NOT NULL DEFAULT 0,
    rejected         INTEGER      NOT NULL DEFAULT 0,
    waited_hours_max NUMERIC(8,1),                        -- the longest any member in this sheet had been waiting for its id
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX offline_uploads_policy ON integration.offline_uploads (broker_id, policy_id, created_at DESC);
CREATE INDEX offline_uploads_tpa ON integration.offline_uploads (tpa_id, created_at DESC);

CREATE TABLE integration.offline_upload_rows (
    upload_id           UUID         NOT NULL REFERENCES integration.offline_uploads (id) ON DELETE CASCADE,
    line                INTEGER      NOT NULL,            -- the line number in the sheet, as the person sees it in Excel
    employee_code       VARCHAR(80),
    relation            VARCHAR(80),
    tpa_member_id       VARCHAR(80),
    tpa_member_name     VARCHAR(200),
    ecard_url           VARCHAR(500),
    cover_start_date    VARCHAR(40),
    endorsement_ref     VARCHAR(80),
    outcome             VARCHAR(12)  NOT NULL CHECK (outcome IN ('ASSIGNED', 'REASSIGNED', 'UPDATED', 'UNCHANGED', 'REJECTED')),
    reason_code         VARCHAR(40),
    reason              VARCHAR(300),
    roster_id           BIGINT,
    previous_tpa_member_id VARCHAR(80),
    PRIMARY KEY (upload_id, line)
);
