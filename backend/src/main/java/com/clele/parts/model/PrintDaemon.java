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
     * The label media the daemon detected in the printer over IPP, reported on every poll. Read
     * only — it reflects what is physically loaded, so changing the roll updates it automatically.
     * Null until the daemon has managed to read the printer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", length = 20)
    private MediaKind mediaKind;

    /** Width (mm) across the print head — the narrow edge of the label. */
    @Column(name = "media_width_mm")
    private Integer mediaWidthMm;

    /** Length (mm) along the feed direction; null/0 for continuous tape, which has no fixed length. */
    @Column(name = "media_length_mm")
    private Integer mediaLengthMm;

    /** Raw IPP media keyword, e.g. "om_brother-label-17x54mm_17x54mm". Diagnostic aid. */
    @Column(name = "media_name", length = 128)
    private String mediaName;

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
