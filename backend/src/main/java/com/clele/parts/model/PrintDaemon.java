package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "print_daemon")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintDaemon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null while unclaimed (PENDING); set to the claiming user once ACTIVE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    /** Hostname reported at registration, shown to the user when picking a daemon. */
    @Column(name = "name", nullable = false)
    private String name;

    /** BCrypt hash of the daemon's API key; the raw key is only ever returned once, at registration. */
    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    /** Network address of the printer this daemon should send jobs to, set by the owner. */
    @Column(name = "printer_ip", length = 45)
    private String printerIp;

    /**
     * Width (mm) of the continuous label tape physically loaded in the printer — must match, or
     * the printer rejects the job as a media error. Not discoverable from software; set by the
     * owner. Standard Brother QL continuous widths: 12/29/38/50/54/62mm.
     */
    @Column(name = "tape_width_mm", nullable = false)
    @Builder.Default
    private Integer tapeWidthMm = 62;

    /**
     * Version the daemon reports on every call ({@code X-Daemon-Version}), so it reflects the
     * binary actually running even after an in-place upgrade. Null for a daemon that never
     * reported one (pre-versioning build).
     */
    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "registered_ip", nullable = false, length = 45)
    private String registeredIp;

    @Column(name = "last_seen_ip", nullable = false, length = 45)
    private String lastSeenIp;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DaemonStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
