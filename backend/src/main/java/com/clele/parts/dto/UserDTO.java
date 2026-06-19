package com.clele.parts.dto;

import lombok.*;

import java.util.Set;

/** User account as returned by the API. Never contains the password hash. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {
    private Long id;
    private String email;
    private String fullName;
    private String phone;
    private Set<String> permissions;
    private Long defaultLocationId;
    private String defaultLocationName;
    /** Whether the user has OctoPart (Nexar) credentials configured. Used to gate the UI. */
    private boolean hasOctopartCredentials;
}
