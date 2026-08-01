package com.clele.parts.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SwitchOrganisationRequest {

    @NotNull
    private Long organisationId;
}
