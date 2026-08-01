package com.clele.parts.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * One permission a user holds in one organisation — a row of
 * {@code app_user_organisation_permission}. Modelled as an embeddable element collection on
 * {@link AppUser} rather than an entity: it has no identity of its own beyond the triple, and it is
 * always loaded and rewritten as a whole set for a user.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class OrganisationPermission {

    @Column(name = "organisation_id", nullable = false)
    private Long organisationId;

    @Column(name = "permission", nullable = false, length = 64)
    private String permission;
}
