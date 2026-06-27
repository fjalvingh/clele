package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRequest {
    @NotBlank(message = "Project name is required")
    private String name;
    private String description;
    @Min(value = 1, message = "Instance count must be at least 1")
    private int instanceCount = 1;
}
