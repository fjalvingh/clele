-- API keys for the read-only MCP endpoint (/api/mcp), which an AI assistant talks to instead of
-- the SPA. There is no session there: the key *is* the credential, and it carries both halves of
-- the context every read needs -- which user it acts as, and which organisation it may see. Pinning
-- the organisation on the key rather than resolving it the session's way is deliberate: an MCP
-- client has no way to switch organisation and no screen to notice it was moved, so a key that
-- silently followed app_user.last_organisation_id would answer questions about a catalogue the
-- holder never asked about.
CREATE TABLE mcp_api_key (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    organisation_id BIGINT NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    -- What the key is for, in the owner's words ("Claude Desktop on the laptop"). Shown in the list.
    name            VARCHAR(100) NOT NULL,
    -- BCrypt hash of the secret half of the token. The token itself is shown once, at creation, and
    -- is unrecoverable afterwards -- same rule as the print daemon's api_key_hash.
    key_hash        VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    -- Touched at most once a minute by the auth filter, so an owner can tell a live key from a
    -- forgotten one before revoking it.
    last_used_at    TIMESTAMP
);

CREATE INDEX idx_mcp_api_key_user ON mcp_api_key (user_id);
