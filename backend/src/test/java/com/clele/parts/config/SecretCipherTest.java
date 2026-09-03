package com.clele.parts.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The cipher that keeps an organisation's Anthropic key out of a database dump.
 *
 * <p>Pinned here: it round-trips, it is not a no-op, a changed secret fails loudly rather than
 * returning rubbish, and with no secret configured it refuses to work instead of falling back to
 * something built into the jar — which would be plaintext with extra steps.
 */
class SecretCipherTest {

    @Test
    void roundTripsUnderTheSameSecret() {
        SecretCipher cipher = new SecretCipher("a-long-random-installation-secret");
        String encrypted = cipher.encrypt("sk-ant-api03-abcdef");

        assertThat(encrypted).doesNotContain("sk-ant");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-ant-api03-abcdef");
    }

    /** A fresh IV per call, so identical keys do not store identically. */
    @Test
    void encryptsTheSameValueDifferentlyEachTime() {
        SecretCipher cipher = new SecretCipher("a-long-random-installation-secret");
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void aChangedSecretFailsLoudly() {
        String encrypted = new SecretCipher("the-original-secret").encrypt("sk-ant-api03-abcdef");
        SecretCipher other = new SecretCipher("a-different-secret");

        assertThrows(SecretCipher.SecretUnreadableException.class, () -> other.decrypt(encrypted));
    }

    @Test
    void withNoSecretItIsUnavailableRatherThanInsecure() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.available()).isFalse();
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("sk-ant-api03-abcdef"));
    }
}
