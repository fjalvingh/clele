package com.clele.parts.model;

/**
 * Why AI is or is not usable for an organisation. The whole reason this is an enum rather than a
 * boolean is that the two unusable states a user has to be able to tell apart — "nobody set this
 * up" and "the key stopped working" — look identical from the outside: both produce a lookup that
 * returns nothing.
 *
 * <p>{@link #usable()} says whether a lookup is worth attempting. States that are not usable are
 * the ones the SPA falls back from: it stops offering the AI sources, keeps the free ones (the
 * organisation's own catalogue, the component cache, the DuckDuckGo searches), and shows which of
 * these states it is in.
 */
public enum AiState {

    /** A key is stored and nothing has rejected it. */
    READY(true),

    /** This organisation has no Anthropic key. The default state of a new organisation. */
    NOT_CONFIGURED(false),

    /**
     * A key is stored but the server cannot decrypt anything: {@code APP_SECRET_KEY} is not set.
     * An installation problem, not a tenant one, which is why it reads differently.
     */
    SERVER_SECRET_MISSING(false),

    /**
     * The stored ciphertext did not decrypt — {@code APP_SECRET_KEY} was changed or lost after the
     * key was saved. Only re-entering the key fixes it, so say exactly that.
     */
    KEY_UNREADABLE(false),

    /** Anthropic rejected the key (401/403). Wrong key, or one that has been revoked. */
    KEY_REJECTED(false),

    /** Anthropic refused the call for want of credit. The organisation has to top up its account. */
    NO_CREDITS(false);

    private final boolean usable;

    AiState(boolean usable) {
        this.usable = usable;
    }

    /** Whether a lookup should be attempted at all, or the caller should fall back instead. */
    public boolean usable() {
        return usable;
    }
}
