-- Organisation invitations.
--
-- An Organisation Admin can no longer create or attach accounts directly; they invite an email
-- address instead. The invitation carries the permissions the invitee gets in the organisation once
-- they accept, so accepting is a single step for the invitee and needs no follow-up by the admin.
--
-- The token is the only credential on the accept/decline links, which are followed by someone with
-- no session at all — hence a long random value, a single-use status, and an expiry.

CREATE TABLE organisation_invitation (
    id              BIGSERIAL PRIMARY KEY,
    token           VARCHAR(64)  NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL,
    organisation_id BIGINT       NOT NULL REFERENCES organisation (id) ON DELETE CASCADE,
    -- The inviting admin, named in the mail. Nulled rather than blocking their account deletion.
    invited_by_id   BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    responded_at    TIMESTAMP
);

CREATE INDEX idx_organisation_invitation_organisation ON organisation_invitation (organisation_id);
CREATE INDEX idx_organisation_invitation_email ON organisation_invitation (email);

-- Permissions the invitee will hold in this organisation after accepting.
CREATE TABLE organisation_invitation_permission (
    invitation_id BIGINT      NOT NULL REFERENCES organisation_invitation (id) ON DELETE CASCADE,
    permission    VARCHAR(64) NOT NULL,
    PRIMARY KEY (invitation_id, permission)
);
