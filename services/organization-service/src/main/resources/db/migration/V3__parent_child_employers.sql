-- Legacy employer_child_companies: a parent employer (the group head) with child employers (subsidiaries). One level only; both
-- sides belong to the same broker. Behaviour that depends on it (cross-entity employee lookup, config inheritance) is switched by the
-- PARENT_CHILD_LOGIC config on the parent, as in the legacy deployment flag.
ALTER TABLE organization.employers ADD COLUMN parent_employer_id BIGINT REFERENCES organization.employers(id);
ALTER TABLE organization.employers ADD CONSTRAINT employers_not_own_parent CHECK (parent_employer_id IS NULL OR parent_employer_id <> id);
CREATE INDEX idx_employers_parent ON organization.employers(parent_employer_id) WHERE parent_employer_id IS NOT NULL;
