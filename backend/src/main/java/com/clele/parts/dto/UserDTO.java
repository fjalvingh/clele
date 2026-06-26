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
    /** The user's last-used location (pre-selects the next stock add). */
    private Long lastLocationId;
    private String lastLocationName;
    /** Whether the user has OctoPart (Nexar) credentials configured. Used to gate the UI. */
    private boolean hasOctopartCredentials;
}
