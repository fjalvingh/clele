package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Add a part to a project's part list, or change how many of it the project needs. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectPartRequest {
    @NotNull(message = "Part ID is required")
    private Long partId;
    @Min(value = 1, message = "Quantity per instance must be at least 1")
    private int qtyPerInstance = 1;
    private String notes;
}
