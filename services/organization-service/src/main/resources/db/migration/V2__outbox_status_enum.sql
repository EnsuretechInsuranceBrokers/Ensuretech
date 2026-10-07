-- Align outbox.outbox_events.status with the shared design: public.outbox_status_enum.
-- Safe whichever service created the table first (a no-op if it is already the enum).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                   WHERE t.typname = 'outbox_status_enum' AND n.nspname = 'public') THEN
        CREATE TYPE public.outbox_status_enum AS ENUM ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD');
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'outbox' AND table_name = 'outbox_events'
                 AND column_name = 'status' AND data_type = 'character varying') THEN
        DROP INDEX IF EXISTS outbox.idx_outbox_pending;
        ALTER TABLE outbox.outbox_events DROP CONSTRAINT IF EXISTS outbox_events_status_check;
        ALTER TABLE outbox.outbox_events ALTER COLUMN status DROP DEFAULT;
        ALTER TABLE outbox.outbox_events
            ALTER COLUMN status TYPE public.outbox_status_enum USING status::public.outbox_status_enum;
        ALTER TABLE outbox.outbox_events ALTER COLUMN status SET DEFAULT 'PENDING';
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox.outbox_events(created_at)
    WHERE status IN ('PENDING', 'FAILED') AND is_test_data = FALSE;
