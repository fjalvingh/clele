package com.clele.parts.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Making and storing the opaque strings the OAuth flow passes around.
 *
 * <p><b>SHA-256, not BCrypt</b> — the opposite choice from a password, and from the MCP API key
 * whose id makes it findable. These values are 256 bits of randomness generated here, so there is
 * no low-entropy secret to slow an attacker down over; what is needed instead is a hash the token
 * endpoint can look a row up by, which BCrypt's per-row salt makes impossible.
 */
public final class OAuthTokens {

    private OAuthTokens() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new opaque credential: 256 bits, URL-safe, no padding. */
    public static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form of a credential: lowercase hex SHA-256, 64 characters. */
    public static String hash(String token) {
        return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * The PKCE S256 transformation: BASE64URL(SHA256(ASCII(verifier))), unpadded — the exact form
     * the client computed, so the comparison is a string equality and nothing has to be decoded.
     */
    public static String s256(String codeVerifier) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
