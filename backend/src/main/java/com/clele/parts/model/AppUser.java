package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    private String phone;

    /** Per-user OctoPart (Nexar) API client id — the user's own free contract. Optional. */
    @Column(name = "octopart_client_id")
    private String octopartClientId;

    /** Per-user OctoPart (Nexar) API client secret. Optional, never exposed via the API. */
    @Column(name = "octopart_client_secret")
    private String octopartClientSecret;

    /** The location this user most recently added stock to; pre-selects the next add. Optional. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_location_id")
    private Location lastLocation;

    /** 8-digit date string of the last changelog entry the user acknowledged (e.g. "20260623"). */
    @Column(name = "last_read_changes", length = 8)
    private String lastReadChanges;

    /** Whether label prints go through the browser print dialog or a paired daemon. */
    @Enumerated(EnumType.STRING)
    @Column(name = "print_method", nullable = false, length = 20)
    @Builder.Default
    private PrintMethod printMethod = PrintMethod.BROWSER;

    /** Whether printing a label also prints a second label with the part's Clele barcode. */
    @Column(name = "print_barcode_label", nullable = false)
    @Builder.Default
    private boolean printBarcodeLabel = false;

    /** The daemon to use when printMethod is DAEMON. Optional. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_daemon_id")
    private PrintDaemon preferredDaemon;

    /** The organisations this user may work in. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "app_user_organisation",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "organisation_id"))
    @Builder.Default
    private Set<Organisation> organisations = new HashSet<>();

    /**
     * The organisation this user last selected. Seeds the current organisation when a new session
     * starts; not a managed account setting (same idea as {@link #lastLocation}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_organisation_id")
    private Organisation lastOrganisation;

    /**
     * Global permissions — in force whatever organisation the user is in. In practice only
     * {@link Permissions#GLOBAL_ADMIN}; everything else lives in
     * {@link #organisationPermissions}.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_permission", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission", nullable = false)
    @Builder.Default
    private Set<String> permissions = new HashSet<>();

    /** Permissions held per organisation (V37). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_organisation_permission",
            joinColumns = @JoinColumn(name = "user_id"))
    @Builder.Default
    private Set<OrganisationPermission> organisationPermissions = new HashSet<>();

    /** Whether this user is a Global Administrator (implies every per-organisation permission). */
    public boolean isGlobalAdmin() {
        return permissions.contains(Permissions.GLOBAL_ADMIN);
    }

    /**
     * The permissions this user actually holds in the given organisation. A Global Administrator
     * holds all of them everywhere — without that, a newly created organisation would have no
     * member able to add its first user.
     */
    public Set<String> permissionsIn(Long organisationId) {
        if (isGlobalAdmin()) {
            return new HashSet<>(Permissions.PER_ORGANISATION);
        }
        return organisationPermissions.stream()
                .filter(p -> p.getOrganisationId().equals(organisationId))
                .map(OrganisationPermission::getPermission)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    public boolean hasPermissionIn(Long organisationId, String permission) {
        return permissionsIn(organisationId).contains(permission);
    }

    /** Replace this user's permissions in one organisation, leaving the others untouched. */
    public void setPermissionsIn(Long organisationId, Set<String> granted) {
        organisationPermissions.removeIf(p -> p.getOrganisationId().equals(organisationId));
        for (String permission : granted) {
            organisationPermissions.add(new OrganisationPermission(organisationId, permission));
        }
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
