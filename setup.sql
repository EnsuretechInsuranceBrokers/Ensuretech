-- Ensuretech: one-time database setup.   Run as a PostgreSQL superuser:
--     psql -h localhost -U postgres -f setup.sql
-- Safe to re-run: existing roles/databases are left alone.
-- Every service owns its own database. They cannot share one: each creates an "outbox" schema with the same tables.
-- Tables are created by each service's own migrations (Flyway / Django) the first time it starts.

SELECT 'CREATE ROLE ensuretech LOGIN PASSWORD ''ensuretech'' CREATEDB'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ensuretech')\gexec

SELECT format('CREATE DATABASE %I OWNER ensuretech ENCODING ''UTF8'' TEMPLATE template0', d)
FROM unnest(ARRAY[
    'ensuretech_identity',       -- identity-service      (Django)
    'ensuretech_org',            -- organization-service  (Spring)
    'ensuretech_policy',         -- policy-service        (Spring)
    'ensuretech_billing',        -- billing-service       (Spring)
    'ensuretech_endorsement',    -- endorsement engine    (Django)
    'ensuretech_integration',    -- integration-service   (Spring)
    'ensuretech_sheetgen',       -- sheet-generator       (Spring)
    'ensuretech_notification'    -- notification-service  (Django)
]) AS d
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = d)\gexec

-- Only if you use FerretDB (MongoDB-compatible, on PostgreSQL) instead of real MongoDB for read-service. It MUST be UTF8:
-- SELECT 'CREATE DATABASE ferretdb OWNER ensuretech ENCODING ''UTF8'' TEMPLATE template0'
-- WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ferretdb')\gexec

\echo 'Databases ready. Next: start each service once (it migrates itself), then run seed.sql.'
