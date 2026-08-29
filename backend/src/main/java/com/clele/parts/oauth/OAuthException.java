package com.clele.parts.oauth;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An OAuth error, in the shape the specification requires: a code from the registry, a human
 * description, and the knowledge of whether it may be handed back to the client or has to be shown
 * to the user.
 *
 * <p>That last distinction is the security-relevant one. An error is normally reported by
 * redirecting to the client's {@code redirect_uri} with {@code ?error=…}, but <b>not when the
 * client or the redirect URI itself is what failed validation</b> — redirecting there would make
 * this app an open redirector for any address an attacker cared to name. Those errors have no
 * {@link #redirectUri} and are rendered here instead.
 */
@Getter
public class OAuthException extends RuntimeException {

    private final String error;
    private final String description;
    private final HttpStatus status;
    /** Null when the error must be shown to the user rather than sent to the client. */
    private final String redirectUri;
    private final String state;

    private OAuthException(String error, String description, HttpStatus status,
                           String redirectUri, String state) {
        super(error + ": " + description);
        this.error = error;
        this.description = description;
        this.status = status;
        this.redirectUri = redirectUri;
        this.state = state;
    }

    /** An error the user has to see, because it cannot safely be sent anywhere. */
    public static OAuthException shown(String error, String description, HttpStatus status) {
        return new OAuthException(error, description, status, null, null);
    }

    public static OAuthException shown(String error, String description) {
        return shown(error, description, HttpStatus.BAD_REQUEST);
    }

    /** An error to hand back to the client at its (already validated) redirect URI. */
    public static OAuthException redirected(String error, String description,
                                            String redirectUri, String state) {
        return new OAuthException(error, description, HttpStatus.BAD_REQUEST, redirectUri, state);
    }

    public boolean isRedirectable() {
        return redirectUri != null;
    }
}
