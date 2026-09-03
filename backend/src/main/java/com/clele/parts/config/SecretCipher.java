package com.clele.parts.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Encrypts the secrets this app stores on behalf of a tenant — today an organisation's Anthropic
 * API key, which bills by the call and so must not be readable from a database dump.
 *
 * <p>AES-256-GCM with a PBKDF2-derived key ({@link Encryptors#delux}), keyed by
 * {@code app.secret-key} from the environment. Every {@link #encrypt} uses a fresh random IV, so
 * the same key saved twice stores differently — do not compare ciphertexts.
 *
 * <p><b>The secret is not optional and there is no fallback.</b> With {@code APP_SECRET_KEY} unset
 * the cipher reports {@link #available()} false and refuses to work, rather than quietly falling
 * back to a built-in constant: a value encrypted under a constant that ships in the jar is
 * plaintext with extra steps, and nothing on screen would say so. The callers turn the unavailable
 * cipher into a message that names the missing variable.
 *
 * <p>Changing {@code APP_SECRET_KEY} on an existing installation makes stored secrets undecryptable
 * — deliberately visible as {@code KEY_UNREADABLE} rather than as a mystery API failure, and fixed
 * by pasting the key in again.
 */
@Component
@Slf4j
public class SecretCipher {

    /**
     * PBKDF2 salt. Fixed and public on purpose: it is not the secret, {@code app.secret-key} is.
     * A per-value salt would have to be stored beside every value and buys nothing here — there is
     * one password for the whole installation, so there is one derived key whatever the salt.
     */
    private static final String SALT = "7c4b1e9a63f0d825";

    private final TextEncryptor encryptor;

    public SecretCipher(@Value("${app.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            this.encryptor = null;
            log.warn("app.secret-key (APP_SECRET_KEY) is not set: per-organisation AI keys cannot "
                    + "be stored or read. Set it to any long random string to enable them.");
        } else {
            this.encryptor = Encryptors.delux(secretKey, SALT);
        }
    }

    /** Whether the installation is configured to hold secrets at all. */
    public boolean available() {
        return encryptor != null;
    }

    /**
     * @throws IllegalStateException when no {@code app.secret-key} is configured — check
     *         {@link #available()} first and tell the user which variable to set.
     */
    public String encrypt(String plaintext) {
        requireAvailable();
        return encryptor.encrypt(plaintext);
    }

    /**
     * @throws IllegalStateException when no {@code app.secret-key} is configured
     * @throws SecretUnreadableException when the ciphertext does not decrypt under the current
     *         secret — almost always because the secret changed since it was written
     */
    public String decrypt(String ciphertext) {
        requireAvailable();
        try {
            return encryptor.decrypt(ciphertext);
        } catch (Exception e) {
            throw new SecretUnreadableException(e);
        }
    }

    private void requireAvailable() {
        if (encryptor == null) {
            throw new IllegalStateException("app.secret-key is not configured");
        }
    }

    /** A stored secret that cannot be decrypted under the current {@code app.secret-key}. */
    public static class SecretUnreadableException extends RuntimeException {
        public SecretUnreadableException(Throwable cause) {
            super("Stored secret could not be decrypted with the current app.secret-key", cause);
        }
    }
}
