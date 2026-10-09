-- NOT needed with the full setup.sql, which already contains these rows. Only for databases built with `setup.sql -v databases_only=1`.
-- Run AFTER organization-service has started once (its tables must exist):
--     psql -h localhost -U ensuretech -d ensuretech_org -f seed.sql      (password: ensuretech)
-- No API creates brokers; every broker_id that appears in a user's token needs a row here.
INSERT INTO organization.brokers (id, code, name, contact_email) VALUES
    (1, 'B1', 'Broker One', 'ops@broker-one.test'),
    (2, 'B2', 'Broker Two (for tenant-isolation checks)', 'ops@broker-two.test')
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('organization.brokers', 'id'), GREATEST((SELECT max(id) FROM organization.brokers), 1));
