package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    /** Parent location in the hierarchy. NULL = root. Must be in the same organisation. */
    private Long parentId;

    /** Slots in this drawer, or NULL when it is not divided. Only allowed on a leaf location. */
    @Positive(message = "Number of slots must be positive")
    private Integer slotCount;
}
