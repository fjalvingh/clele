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

    /** Reassign the location to another user. Admin-only; ignored on create. */
    private Long ownerId;
}
