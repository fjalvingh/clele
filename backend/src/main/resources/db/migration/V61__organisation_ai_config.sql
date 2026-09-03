-- Per-organisation AI credentials.
--
-- The AI lookup is the only thing in this app that spends real money per call (5-13 cents for a
-- part search), and until now every organisation spent it through one app-wide key -- so one
-- enthusiastic tenant ran up a bill the others shared and nobody could see whose it was. The key
-- moves onto the organisation: each tenant brings its own Anthropic contract and pays its own bill,
-- and an organisation without one simply has no AI (the catalogue, the component cache and the
-- DuckDuckGo searches all still work, which is what makes that an acceptable state rather than a
-- broken one).
--
-- ai_api_key holds CIPHERTEXT, never the key itself -- AES-256-GCM under the server's
-- APP_SECRET_KEY, see config/SecretCipher. A database dump therefore carries no usable credential,
-- which is the point: unlike the per-user OctoPart secret next door, this one bills by the call.
ALTER TABLE organisation ADD COLUMN ai_api_key TEXT;

-- Last four characters of the plaintext key, so the admin screen can show *which* key is stored
-- ("...4Xa2") without the server having to decrypt it to render a page. Four characters identify a
-- key to the person who pasted it and are useless to anybody else.
ALTER TABLE organisation ADD COLUMN ai_key_hint VARCHAR(8);

-- Model this organisation asks for. NULL means "the installation default" (anthropic.model), which
-- is what almost every organisation should leave it as -- an organisation that wants a stronger
-- model pays for it out of its own key, so it may choose.
ALTER TABLE organisation ADD COLUMN ai_model VARCHAR(100);

-- Why AI last stopped working, as an AiState name (NO_CREDITS, KEY_REJECTED, ...), or NULL while it
-- is working. Persisted rather than discovered per request because the two states the user must be
-- able to tell apart -- "nobody set this up" and "the credits ran out" -- are otherwise
-- indistinguishable from the outside: both produce a lookup that returns nothing. Cleared by the
-- next successful call or by the connection test on the admin screen.
ALTER TABLE organisation ADD COLUMN ai_status_code    VARCHAR(32);
ALTER TABLE organisation ADD COLUMN ai_status_message TEXT;
ALTER TABLE organisation ADD COLUMN ai_status_at      TIMESTAMP;
