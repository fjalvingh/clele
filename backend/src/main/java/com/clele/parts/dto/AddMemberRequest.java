package com.clele.parts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Add an existing user account to the current organisation, identified by its email. */
@Data
public class AddMemberRequest {

    @NotBlank
    @Email
    private String email;
}
