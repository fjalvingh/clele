package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/** The organisation's AI configuration as the admin screen sees it. Never returns the key itself. */
@Data
@Builder
public class AiConfigDTO {

    private boolean hasApiKey;

    /** Last four characters of the stored key, so the admin can tell which one is in place. */
    private String keyHint;

    /** The organisation's chosen model, or null when it follows the installation default. */
    private String model;

    /** The installation default, shown as the placeholder for an empty {@code model}. */
    private String defaultModel;

    /** {@code AiState} name — same value {@code /api/ai/status} reports. */
    private String state;

    private boolean usable;

    private String message;

    /** When the failure in {@code state} was recorded, ISO-8601. Null when READY. */
    private String since;

    /**
     * Whether the server can hold secrets at all ({@code APP_SECRET_KEY} is set). False makes the
     * key box refuse to save, with the variable named — the alternative is an admin who pastes a key,
     * sees it accepted and finds AI still dead.
     */
    private boolean serverSecretConfigured;
}
