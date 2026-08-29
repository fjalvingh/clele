-- OAuth 2.1 for the MCP endpoint (see docs/mcp.md).
--
-- The API key of V57 is all a scripted client needs, but Claude Desktop and claude.ai connect to a
-- remote MCP server by URL alone: there is nowhere in their connector dialog to put a header. What
-- they expect instead is an authorization server that registers them on the spot (RFC 7591) and
-- walks the user through an ordinary browser consent. This app is both the resource server and that
-- authorization server -- a second identity provider would be absurd for an installation whose
-- users already have accounts here.
--
-- Everything issued is a token *for this app*, held by an app user, pinned to one organisation.
-- Nothing here federates to anyone else.

-- A client that registered itself. There is no admin step: registration is open, which is what
-- "auto-registering" means, and is safe because a client cannot do anything until a logged-in user
-- has approved it in the browser -- and the approval is what grants access, not the registration.
CREATE TABLE oauth_client (
    client_id                  VARCHAR(64) PRIMARY KEY,
    -- What the client called itself at registration ("Claude"). Shown on the consent screen, so it
    -- is attacker-controlled text: the screen must present it as a claim, never as a fact.
    client_name                VARCHAR(200),
    -- Newline-separated. Matched exactly at /authorize -- never by prefix, which is how open
    -- redirectors are built.
    redirect_uris              TEXT NOT NULL,
    grant_types                VARCHAR(200) NOT NULL,
    scope                      VARCHAR(200),
    token_endpoint_auth_method VARCHAR(40) NOT NULL,
    -- Null for a public client (the usual case: a desktop app cannot keep a secret, and PKCE is
    -- what protects it instead).
    client_secret_hash         VARCHAR(100),
    created_at                 TIMESTAMP NOT NULL,
    last_used_at               TIMESTAMP
);

-- One authorization request, from the moment the browser arrives at /authorize until the code is
-- exchanged. The pending request and the issued code are one row because they are one thing: the
-- code is what the row becomes once a user has approved it, and keeping them together means the
-- PKCE challenge, the redirect URI and the resource cannot drift apart from the code they bind.
CREATE TABLE oauth_authorization (
    id                    BIGSERIAL PRIMARY KEY,
    -- Opaque handle carried in the consent URL. Not the code: the browser sees this one.
    request_id            VARCHAR(64) NOT NULL UNIQUE,
    client_id             VARCHAR(64) NOT NULL REFERENCES oauth_client(client_id) ON DELETE CASCADE,
    redirect_uri          TEXT NOT NULL,
    scope                 VARCHAR(200),
    state                 TEXT,
    -- RFC 8707: what the token is to be used against. Recorded here so the audience written on the
    -- token is the one the client asked for, not one the token endpoint invented.
    resource              TEXT,
    code_challenge        VARCHAR(200) NOT NULL,
    code_challenge_method VARCHAR(10) NOT NULL,
    -- Both set at approval; null while the request is still pending.
    user_id               BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
    organisation_id       BIGINT REFERENCES organisation(id) ON DELETE CASCADE,
    -- SHA-256 of the authorization code. Fast to look up, unlike BCrypt, and the code is 256 bits
    -- of randomness with a lifetime measured in seconds -- there is nothing to brute-force.
    code_hash             VARCHAR(64) UNIQUE,
    created_at            TIMESTAMP NOT NULL,
    expires_at            TIMESTAMP NOT NULL,
    -- A code is single-use. This is what makes the second attempt fail.
    consumed_at           TIMESTAMP
);

CREATE INDEX idx_oauth_authorization_expires ON oauth_authorization (expires_at);

-- An issued access token and the refresh token beside it. Opaque and stored hashed: a JWT would
-- have to be validated against a key this app would also have to manage, for tokens only this app
-- ever sees.
CREATE TABLE oauth_token (
    id                 BIGSERIAL PRIMARY KEY,
    access_token_hash  VARCHAR(64) NOT NULL UNIQUE,
    refresh_token_hash VARCHAR(64) UNIQUE,
    client_id          VARCHAR(64) NOT NULL REFERENCES oauth_client(client_id) ON DELETE CASCADE,
    -- Which approval this token came from. Kept so that a replayed authorization code can take the
    -- tokens it already produced down with it, as OAuth 2.1 requires: a code presented twice means
    -- one of the two presenters is not the client, and neither may keep what it got.
    authorization_id   BIGINT REFERENCES oauth_authorization(id) ON DELETE SET NULL,
    user_id            BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    -- The organisation the user picked on the consent screen. Same pinning as an MCP API key, for
    -- the same reason: the client has no way to switch one and no screen to notice it moved.
    organisation_id    BIGINT NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    scope              VARCHAR(200),
    -- The resource this token was issued for. Checked on every call: a token minted for something
    -- else must not be accepted here, however valid it is elsewhere.
    audience           TEXT,
    expires_at         TIMESTAMP NOT NULL,
    refresh_expires_at TIMESTAMP,
    revoked_at         TIMESTAMP,
    created_at         TIMESTAMP NOT NULL,
    last_used_at       TIMESTAMP
);

CREATE INDEX idx_oauth_token_user ON oauth_token (user_id);
CREATE INDEX idx_oauth_token_expires ON oauth_token (expires_at);
