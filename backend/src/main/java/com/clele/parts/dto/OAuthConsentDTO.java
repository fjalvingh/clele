package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What the consent screen shows. {@code clientName} and {@code redirectHost} are the client's own
 * claims about itself — the screen presents them as such, since anyone may register a client
 * calling itself anything.
 */
@Data
@Builder
public class OAuthConsentDTO {
    private String requestId;
    private String clientName;
    /** Host the browser will be sent back to, so the user can see where this is really going. */
    private String redirectHost;
    private String scope;
    /** The organisations this user may grant access to; the token is pinned to the one chosen. */
    private List<OrganisationDTO> organisations;
    private Long defaultOrganisationId;
}
