package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stored definition for a pack of parts that differ in exactly one value — a resistor kit, a
 * capacitor assortment, a set of the same connector in different pin counts.
 *
 * <p>Every text field is a <em>template</em>: the placeholder <code>${value}</code> is replaced by
 * each of {@link #values} in turn to produce one part. That is why none of them is typed more
 * strictly than {@code String} — {@code "${value}Ω"} is not a number and
 * {@code "https://…/${value}.pdf"} is not a validated URL until it has been expanded.
 */
@Entity
@Table(name = "part_kit_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartKitTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The organisation this template belongs to. Set once at creation and never changed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    /** The kit's own name, unique within the organisation — "E12 resistors 1/4W 5%". */
    @Column(nullable = false)
    private String name;

    /** Free notes about the kit itself, not about the parts it generates. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "part_number_template", nullable = false, columnDefinition = "TEXT")
    private String partNumberTemplate;

    @Column(name = "personal_number", nullable = false)
    private boolean personalNumber;

    @Column(name = "manufacturer_template", columnDefinition = "TEXT")
    private String manufacturerTemplate;

    @Column(name = "description_template", columnDefinition = "TEXT")
    private String descriptionTemplate;

    @Column(name = "details_template", columnDefinition = "TEXT")
    private String detailsTemplate;

    @Column(name = "footprint_template", columnDefinition = "TEXT")
    private String footprintTemplate;

    @Column(name = "datasheet_url_template", columnDefinition = "TEXT")
    private String datasheetUrlTemplate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** Keyed by {@code spec_definition.json_name}, exactly as {@code part.specs} is. Values are templates. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> specs;

    /** Tag <em>names</em> — resolved to rows at generate time, so a tag may hold {@code ${value}} too. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "part_kit_template_tag",
            joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "tag", nullable = false)
    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<PartKitTemplateValue> values = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private AppUser createdBy;

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
