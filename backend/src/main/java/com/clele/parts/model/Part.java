package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "part")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Part {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique within the owning organisation, not globally (see V36). */
    @Column(name = "part_number", nullable = false)
    private String partNumber;

    /** The organisation this part belongs to. Set once at creation and never changed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String details;

    private String manufacturer;

    @Column(length = 64)
    private String footprint;

    @Column(length = 128)
    private String mpn;

    @Column(name = "octopart_id", length = 64)
    private String octopartId;

    /** True when {@link #partNumber} is a user-assigned internal code rather than a real manufacturer part number. */
    @Column(name = "personal_number", nullable = false)
    private boolean personalNumber;

    @Column(name = "datasheet_url", columnDefinition = "TEXT")
    private String datasheetUrl;

    /**
     * The part's textual spec values run together — the search projection the Parts free-text index
     * covers, maintained by {@code PartSpecValueService.sync} and never set by hand.
     *
     * <p>It exists because V43's single concatenated tsvector is load-bearing (a tsquery ANDs its
     * terms, so "transistor sot-23" must find both in one vector) and an expression index cannot
     * reach into {@code part_spec_value}. This is a search projection, not a stored rendering: drift
     * costs a missed hit, not a wrong number on screen, and the same write path rebuilds it.
     */
    @Column(name = "spec_text", columnDefinition = "TEXT")
    private String specText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** The user who created this part. Set once at creation and never changed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "part_tag",
            joinColumns = @JoinColumn(name = "part_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();

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
