package com.clele.parts.dto;

import lombok.*;

/** Whether the current user has OctoPart credentials set. Never exposes the secret itself. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OctopartCredentialsStatusDTO {
    private boolean hasClientId;
    private boolean hasClientSecret;
    /** Echoed back so the user can see which client id is stored (the id is not secret). */
    private String clientId;
}
