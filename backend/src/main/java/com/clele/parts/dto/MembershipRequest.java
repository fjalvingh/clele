package com.clele.parts.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Add a user to an organisation (All Users screen). */
@Data
public class MembershipRequest {
    @NotNull(message = "An organisation is required")
    private Long organisationId;
}
