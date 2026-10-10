-- Announcements: what a broker shows employers' HR users (legacy `announcements` + broker/employer/type mappings).
--   kind     : BANNER, CAROUSEL or NOTICE            position : where the portal shows it
--   targeting: every employer of the broker (all_employers) or an explicit list
--   lifetime : visible from starts_on for ttl_days (legacy time_to_live); no ttl = until retired
-- Images are not stored here (legacy used a media library); `image_url` points at wherever the file lives.
CREATE TABLE organization.announcements (
    id            BIGSERIAL PRIMARY KEY,
    broker_id     BIGINT       NOT NULL REFERENCES organization.brokers(id),
    kind          VARCHAR(10)  NOT NULL CHECK (kind IN ('BANNER', 'CAROUSEL', 'NOTICE')),
    title         VARCHAR(200) NOT NULL,
    content       TEXT         NOT NULL CHECK (length(content) <= 5000),
    link          VARCHAR(500),
    image_url     VARCHAR(500),
    position      VARCHAR(10)  NOT NULL DEFAULT 'TOP' CHECK (position IN ('TOP', 'BOTTOM', 'SIDEBAR')),
    sequence      INT          NOT NULL DEFAULT 0,
    all_employers BOOLEAN      NOT NULL DEFAULT TRUE,
    starts_on     DATE         NOT NULL DEFAULT CURRENT_DATE,
    ttl_days      INT          CHECK (ttl_days IS NULL OR ttl_days BETWEEN 1 AND 3650),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_announcements_broker ON organization.announcements(broker_id, active);

CREATE TABLE organization.announcement_employers (
    announcement_id BIGINT NOT NULL REFERENCES organization.announcements(id) ON DELETE CASCADE,
    employer_id     BIGINT NOT NULL REFERENCES organization.employers(id),
    PRIMARY KEY (announcement_id, employer_id)
);
CREATE INDEX idx_announcement_employers_employer ON organization.announcement_employers(employer_id);
