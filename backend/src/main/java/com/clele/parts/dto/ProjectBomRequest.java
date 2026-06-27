package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectBomRequest {
    @NotNull(message = "Part ID is required")
    private Long partId;
    @Min(value = 1, message = "Quantity per instance must be at least 1")
    private int qtyPerInstance = 1;
    private String notes;
}
