package com.clele.parts.dto;

import lombok.*;

import java.util.List;
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
    /** The organisations this user belongs to. */
    private Set<Long> organisationIds;
    /**
     * The organisation in force for this session, and the ones the user may switch into. Returned
     * on {@code /auth/me} so the sidebar switcher renders without a second request.
     */
    private Long currentOrganisationId;
    private String currentOrganisationName;
    private List<OrganisationDTO> selectableOrganisations;
    /** Whether the user has OctoPart (Nexar) credentials configured. Used to gate the UI. */
    private boolean hasOctopartCredentials;
    /** 8-digit date of the last changelog entry the user acknowledged. Null if never read. */
    private String lastReadChanges;
    /**
     * How this user prints labels (BROWSER/DAEMON) and, for DAEMON, which daemon. Returned on
     * {@code /auth/me} so any print entry point (e.g. the Part detail print button) can route to
     * the right method without a second request.
     */
    private String printMethod;
    private Long preferredDaemonId;
    private boolean printBarcodeLabel;
}
