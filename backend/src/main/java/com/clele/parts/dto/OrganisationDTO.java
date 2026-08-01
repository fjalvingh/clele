package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OrganisationDTO {
    private Long id;
    private String name;
    private String description;
    /** The blueprint organisation copied into every new one; selectable by Global Administrators only. */
    private boolean template;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
