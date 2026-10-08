-- Rounding of the money charged for an endorsement (legacy roundTo: floor / ceil / round to a unit). NONE keeps paise (2 dp).
-- The legacy system rounds only for one portal, so the default is NONE and a policy opts in.
ALTER TABLE policy_svc.policies
    ADD COLUMN premium_rounding VARCHAR(5) NOT NULL DEFAULT 'NONE' CHECK (premium_rounding IN ('NONE', 'FLOOR', 'CEIL', 'ROUND')),
    ADD COLUMN rounding_unit    INTEGER    NOT NULL DEFAULT 1 CHECK (rounding_unit BETWEEN 1 AND 100000);
