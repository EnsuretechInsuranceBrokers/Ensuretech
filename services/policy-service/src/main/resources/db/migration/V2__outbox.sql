-- Shared transactional outbox (same shape as organization-service / the Django side). Idempotent.
CREATE SCHEMA IF NOT EXISTS outbox;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                   WHERE t.typname = 'outbox_status_enum' AND n.nspname = 'public') THEN
        CREATE TYPE public.outbox_status_enum AS ENUM ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD');
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS outbox.outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(60)  NOT NULL,
    aggregate_id   VARCHAR(60)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    payload        JSONB        NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    partition_key  VARCHAR(80),
    status         public.outbox_status_enum NOT NULL DEFAULT 'PENDING',
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
    WHERE status IN ('PENDING', 'FAILED') AND is_test_data = FALSE;
