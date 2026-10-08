-- Enrollment windows (legacy EnrollmentWindowService / EnrollmentWindowStatus): when may an employee join a policy?
--   policy level   : one window (start..end) for the whole policy, optional
--   employee level : an extended / custom window for one employee, with an optional hard close date
--   confirmation   : once an employee confirms, the window is frozen at the dates in force then
--   mid-term       : after the policy window, an employee may still join for `midterm_days` from date of joining or date of upload (policies.midterm_basis)
-- Employees are not stored in this service; they are referenced by the employer's employee code.
CREATE TABLE policy_svc.enrollment_windows (
    policy_id          BIGINT PRIMARY KEY REFERENCES policy_svc.policies(id),
    broker_id          BIGINT      NOT NULL,
    enabled            BOOLEAN     NOT NULL DEFAULT FALSE,
    start_date         DATE,
    end_date           DATE,
    midterm_days       INT         NOT NULL DEFAULT 30 CHECK (midterm_days BETWEEN 1 AND 365),
    allow_after_expiry BOOLEAN     NOT NULL DEFAULT FALSE,
    days_after_expiry  INT         NOT NULL DEFAULT 0 CHECK (days_after_expiry BETWEEN 0 AND 365),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (NOT enabled OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date))
);

CREATE TABLE policy_svc.enrollment_overrides (
    policy_id    BIGINT      NOT NULL REFERENCES policy_svc.policies(id),
    employee_ref VARCHAR(60) NOT NULL,
    broker_id    BIGINT      NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    start_date   DATE        NOT NULL,
    end_date     DATE        NOT NULL,
    close_date   DATE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (policy_id, employee_ref),
    CHECK (end_date >= start_date),
    CHECK (close_date IS NULL OR close_date <= end_date)
);

CREATE TABLE policy_svc.enrollment_confirmations (
    policy_id    BIGINT      NOT NULL REFERENCES policy_svc.policies(id),
    employee_ref VARCHAR(60) NOT NULL,
    broker_id    BIGINT      NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    start_date   DATE,
    end_date     DATE,
    PRIMARY KEY (policy_id, employee_ref)
);

-- Who changed which window, and from what (legacy EnrollmentWindowAuditTrail: POLICY_LEVEL / SINGLE_EMPLOYEE / MULTIPLE_EMPLOYEES)
CREATE TABLE policy_svc.enrollment_window_audit (
    id          BIGSERIAL PRIMARY KEY,
    policy_id   BIGINT      NOT NULL REFERENCES policy_svc.policies(id),
    broker_id   BIGINT      NOT NULL,
    scope       VARCHAR(20) NOT NULL CHECK (scope IN ('POLICY_LEVEL', 'SINGLE_EMPLOYEE', 'MULTIPLE_EMPLOYEES')),
    employees   JSONB       NOT NULL DEFAULT '[]',
    old_value   JSONB,
    new_value   JSONB,
    modified_by VARCHAR(64) NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_enrollment_audit_policy ON policy_svc.enrollment_window_audit(policy_id, at DESC);
