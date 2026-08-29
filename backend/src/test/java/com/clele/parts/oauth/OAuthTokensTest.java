package com.clele.parts.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two transformations the OAuth flow's security rests on. Both are compared as strings against
 * values a client computed independently, so "nearly right" is indistinguishable from wrong —
 * padding, case and encoding all have to match exactly.
 */
class OAuthTokensTest {

    @Test
    @DisplayName("PKCE S256 matches the worked example in RFC 7636")
    void pkceMatchesTheSpecExample() {
        // RFC 7636 Appendix B: this verifier must produce exactly this challenge. If it does, a
        // client's challenge and our verification agree — which is the whole of PKCE.
        assertThat(OAuthTokens.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    @DisplayName("the challenge is URL-safe and unpadded — the form a client sends")
    void challengeIsUrlSafeAndUnpadded() {
        String challenge = OAuthTokens.s256("verifier-with-some-length-to-it-0123456789");
        assertThat(challenge).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    @DisplayName("the stored form is lowercase hex SHA-256, and the same input always hashes alike")
    void hashIsStableHex() {
        String hash = OAuthTokens.hash("a-token");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(OAuthTokens.hash("a-token")).isEqualTo(hash);
        assertThat(OAuthTokens.hash("a-token ")).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("generated credentials are 256 bits and never repeat")
    void randomIsRandom() {
        String one = OAuthTokens.random();
        assertThat(one).hasSize(43);   // 32 bytes, base64url, unpadded
        assertThat(one).isNotEqualTo(OAuthTokens.random());
    }
}
