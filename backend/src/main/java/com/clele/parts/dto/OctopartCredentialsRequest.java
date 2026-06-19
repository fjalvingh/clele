package com.clele.parts.dto;

import lombok.*;

/**
 * Self-service update of the current user's OctoPart (Nexar) credentials. A blank/null
 * {@code clientSecret} leaves the stored secret unchanged (mirrors the password update pattern).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OctopartCredentialsRequest {
    private String clientId;
    private String clientSecret;
}
