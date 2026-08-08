package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One part's use of a {@link PartAttachment}. The same attachment may be linked from any number of
 * parts within its organisation; {@link #displayOrder} is that part's ordering of the attachments
 * it shows, per type.
 */
@Entity
@Table(name = "part_attachment_link")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartAttachmentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id", nullable = false)
    private PartAttachment attachment;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
