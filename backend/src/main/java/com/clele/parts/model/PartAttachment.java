package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A stored photo, datasheet or file. <b>Shared</b>: the parts using it are the
 * {@link PartAttachmentLink} rows pointing here, so one picture can serve every value in a resistor
 * kit rather than being stored once per part.
 */
@Entity
@Table(name = "part_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The organisation whose parts may use this attachment. Held explicitly because the tenant used
     * to be derived through {@code part_id}, and with several parts that derivation is gone.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AttachmentType type;

    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** Original filename for datasheets/attachments; null for PNG-normalized photos. */
    @Column(name = "filename", length = 255)
    private String filename;

    /**
     * The part number of the first part this attachment was used for. Set at creation and never
     * updated — on a shared attachment it says where the file came from, which is the only thing
     * that stays true once other parts start using it too.
     */
    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /** MD5 of {@link #data}, hex. Lets an identical upload reuse this row instead of storing a copy. */
    @Column(name = "md5_hash", nullable = false, length = 32)
    private String md5Hash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
