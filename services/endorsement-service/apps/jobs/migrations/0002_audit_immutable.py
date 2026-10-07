from django.db import migrations

SQL = """
CREATE OR REPLACE FUNCTION endorsement.audit_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_transitions is append-only (% blocked)', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_transitions_no_change
    BEFORE UPDATE OR DELETE ON endorsement.audit_transitions
    FOR EACH ROW EXECUTE FUNCTION endorsement.audit_immutable();
"""
REVERSE = """
DROP TRIGGER IF EXISTS audit_transitions_no_change ON endorsement.audit_transitions;
DROP FUNCTION IF EXISTS endorsement.audit_immutable();
"""


class Migration(migrations.Migration):
    dependencies = [("jobs", "0001_initial")]
    operations = [migrations.RunSQL(SQL, reverse_sql=REVERSE)]
