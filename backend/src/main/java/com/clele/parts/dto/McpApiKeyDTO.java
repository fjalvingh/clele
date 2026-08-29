package com.clele.parts.dto;

import lombok.*;

import java.time.LocalDateTime;

/** An MCP key as listed to its owner. The token itself is never part of this. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpApiKeyDTO {
    private Long id;
    private String name;
    private Long organisationId;
    private String organisationName;
    private LocalDateTime createdAt;
    /** Null until the key is first used; written at most once a minute afterwards. */
    private LocalDateTime lastUsedAt;
}
