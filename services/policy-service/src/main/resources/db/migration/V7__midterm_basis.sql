-- How a mid-term joiner's cover start is decided (legacy EnrollmentMidtermType):
--   DATE_OF_JOINING (default): the date of joining on the sheet.   DATE_OF_UPLOAD: the day the sheet is uploaded (never before the policy starts).
--   NO_MIDTERM: after the policy has started (and is no longer in its inception window) new members cannot be added.
ALTER TABLE policy_svc.policies
    ADD COLUMN midterm_basis VARCHAR(16) NOT NULL DEFAULT 'DATE_OF_JOINING'
        CHECK (midterm_basis IN ('DATE_OF_JOINING', 'DATE_OF_UPLOAD', 'NO_MIDTERM'));
