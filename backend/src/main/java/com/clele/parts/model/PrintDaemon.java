package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
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

    /**
     * Which printer family this daemon drives, chosen by the owner. It decides which of the fields
     * below matters: a network printer needs {@link #printerIp}, a CUPS printer needs
     * {@link #printerQueue} and {@link #mediaKeyword}.
     *
     * <p>{@code @Builder.Default} is required, not decorative: this entity is built with
     * {@code @Builder} and {@code register()} does not set the type, so without it a plain field
     * initialiser is ignored and a null goes into a NOT NULL column.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "printer_type", nullable = false, length = 20)
    @Builder.Default
    private PrinterType printerType = PrinterType.BROTHER_QL;

    /** Network address of the printer this daemon should send jobs to, set by the owner. */
    @Column(name = "printer_ip", length = 45)
    private String printerIp;

    /** CUPS destination name on the daemon's machine, for a USB printer. Set by the owner. */
    @Column(name = "printer_queue", length = 128)
    private String printerQueue;

    /**
     * IPP media keyword the owner picked. Only for a printer that cannot sense its own roll — for
     * those the label size is configuration, not detection, and nothing else can supply it.
     */
    @Column(name = "media_keyword", length = 128)
    private String mediaKeyword;

    /** {@code printer-make-and-model} as reported over IPP. Diagnostic aid. */
    @Column(name = "printer_model", length = 128)
    private String printerModel;

    /**
     * The label media in the printer. For a printer that senses its own roll this is detected and
     * reported on every poll, so changing the roll updates it with no user action; for one that
     * cannot, it is resolved from {@link #mediaKeyword} instead. Null until either has happened.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", length = 20)
    private MediaKind mediaKind;

    /**
     * Width (mm) across the print head — the narrow edge of the label. Fractional because Dymo
     * stock is sized in inches: a common roll is 19.05 mm wide.
     */
    @Column(name = "media_width_mm")
    private BigDecimal mediaWidthMm;

    /** Length (mm) along the feed direction; null for continuous tape, which has no fixed length. */
    @Column(name = "media_length_mm")
    private BigDecimal mediaLengthMm;

    /** Raw IPP media keyword, e.g. "om_brother-label-17x54mm_17x54mm". Diagnostic aid. */
    @Column(name = "media_name", length = 128)
    private String mediaName;

    /**
     * The area the printer can actually mark, reported by the daemon. Each driver derives this from
     * its own geometry, which is why the frontend no longer mirrors per-printer constants.
     */
    @Column(name = "printable_width_mm")
    private BigDecimal printableWidthMm;

    /** Markable length along the feed; null for continuous stock, whose length is ours to choose. */
    @Column(name = "printable_length_mm")
    private BigDecimal printableLengthMm;

    /** Queues and label stock discovered on the daemon's machine; null until it has reported. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities", columnDefinition = "jsonb")
    private DaemonCapabilities capabilities;

    @Column(name = "capabilities_at")
    private LocalDateTime capabilitiesAt;

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
