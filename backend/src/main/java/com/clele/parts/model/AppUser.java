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
