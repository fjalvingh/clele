package com.clele.parts.dto;

import lombok.*;

/**
 * The reply to creating a key: the key as it will be listed, plus the one and only sight of the
 * token. It is not recoverable afterwards — only its BCrypt hash is stored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpApiKeyCreatedDTO {
    private McpApiKeyDTO key;
    private String token;
}
