package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpecDefinitionRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Data type is required")
    private String dataType;

    private String unit;

    private List<String> options;

    private int displayOrder;
}
