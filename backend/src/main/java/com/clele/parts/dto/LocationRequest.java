package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    /** Parent location in the hierarchy. NULL = root. Must be owned by the same user. */
    private Long parentId;

    /** Reassign the location to another user. Admin-only; ignored on create. */
    private Long ownerId;
}
