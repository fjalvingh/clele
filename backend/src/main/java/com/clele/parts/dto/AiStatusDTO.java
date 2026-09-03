package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Whether AI lookups work for the organisation in force, and if not, why.
 *
 * <p>Asked by every screen that offers an AI source, because the alternative — offering the button
 * and letting the call fail — spends a round trip to say what this says up front, and reads as a
 * broken feature rather than an unconfigured one. When {@code usable} is false the SPA falls back
 * to the free sources (the organisation's own catalogue, the component cache, the web searches) and
 * shows {@code message}.
 *
 * <p>Carries no key, and no {@code keyHint} either — a member who cannot configure it has no use
 * for either. The admin screen asks {@code /api/ai/config} for that.
 */
@Data
@Builder
public class AiStatusDTO {

    /** {@code AiState} name: READY, NOT_CONFIGURED, NO_CREDITS, KEY_REJECTED, … */
    private String state;

    /** Whether a lookup is worth attempting. False for every state but READY. */
    private boolean usable;

    /** One sentence for the user, naming what is wrong and who can fix it. Null when READY. */
    private String message;

    /** The model lookups will use — the organisation's choice, or the installation default. */
    private String model;

    /** When the failure in {@code state} was recorded, ISO-8601. Null when READY. */
    private String since;

    /** Whether the current user may go and fix it (ORG_ADMIN), so the message can say "you" or "ask". */
    private boolean canConfigure;
}
