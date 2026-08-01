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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_permission", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission", nullable = false)
    @Builder.Default
    private Set<String> permissions = new HashSet<>();

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
