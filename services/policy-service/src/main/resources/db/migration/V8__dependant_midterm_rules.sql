-- Legacy policy columns is_midterm_enrollement_allowed_for_{spouse,partner,kids}, default_midterm_enrollement_days_for_*, midterm_premium_calculation_from_for_*.
-- Applies to a dependant added to an employee who is ALREADY covered (a mid-term dependant), per member group SPOUSE / PARTNER / KIDS:
--   allowed      false = such a dependant cannot be added mid-term
--   window_days  how long after the marriage/birth the cover may still be added without HR approval (null = no window)
--   start_basis  UPLOAD = cover starts the day the sheet is uploaded; EVENT = at the date of marriage (spouse, partner) or birth (kids)
-- A group with no rule behaves as before: the sheet's own effective date.
CREATE TABLE policy_svc.dependant_midterm_rules (
    policy_id  BIGINT      PRIMARY KEY REFERENCES policy_svc.policies(id) ON DELETE CASCADE,
    broker_id  BIGINT      NOT NULL,
    rules      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
