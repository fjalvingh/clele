-- Per-user OctoPart (Nexar) API credentials + per-user monthly request quota tracking.
-- Each user runs on their own free Nexar contract (limited to ~100 requests/month), so both the
-- credentials and the usage counter live per user.

ALTER TABLE app_user ADD COLUMN octopart_client_id     VARCHAR(255);
ALTER TABLE app_user ADD COLUMN octopart_client_secret VARCHAR(255);

-- One row per (user, calendar month). request_count is the number of Nexar search requests the
-- user has spent in that month; remaining = limit - request_count.
CREATE TABLE octopart_usage (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    period        VARCHAR(7) NOT NULL,         -- 'YYYY-MM'
    request_count INT NOT NULL DEFAULT 0,
    UNIQUE (user_id, period)
);
