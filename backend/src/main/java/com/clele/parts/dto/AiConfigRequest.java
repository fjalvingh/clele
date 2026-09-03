package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Update the organisation's AI configuration (ORG_ADMIN). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiConfigRequest {

    /**
     * The Anthropic API key. Blank or null leaves the stored one unchanged — the same rule the
     * OctoPart secret and the password field follow, since the stored value is never sent to the
     * browser and so cannot be echoed back.
     */
    private String apiKey;

    /** Remove the stored key entirely (which turns AI off for this organisation). */
    private boolean clearApiKey;

    /** Model to use; blank follows the installation default. */
    private String model;
}
