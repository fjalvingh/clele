package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OrganisationRequest {

    @NotBlank
    private String name;

    private String description;
}
