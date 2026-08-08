package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Optional;

@Entity
@Table(name = "spec_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Machine key used as the JSON key inside {@code part.specs}. Unique per organisation. */
    @Column(name = "json_name", nullable = false, length = 100)
    private String jsonName;

    /** The organisation this spec field belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    /** Human-readable display title. */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "data_type", nullable = false, length = 20)
    private String dataType;

    @Column(length = 20)
    private String unit;

    /**
     * What this field measures — a {@link UnitFamily} code, or null.
     *
     * <p>This is what licenses parsing a value string into a number at all: knowing that capacitance
     * is measured in farads is what turns {@code "100nF"} into {@code 1e-7}. It complements
     * {@link #unit} and {@link #metricPrefix} rather than replacing them — those describe how a
     * number is <em>shown</em>, this describes what it <em>is</em>.
     *
     * <p><b>Null means never parse</b>: the values stay text. That is the safe default and
     * deliberately not a gap to fill in for tidiness — an over-eager family is how a 4 KB memory
     * becomes 4000. Note also that the name is not the family: {@code naturalthermalresistance} is
     * °C/W and not {@link UnitFamily#RESISTANCE}, {@code inductancetolerance} is a percentage, and
     * {@code numberofresistors} is a count.
     */
    @Column(name = "unit_family", length = 40)
    private String unitFamily;

    /** When true (NUMBER with a single base SI unit), display/edit the value with metric prefixes. */
    @Column(name = "metric_prefix", nullable = false)
    private boolean metricPrefix;

    @Column(columnDefinition = "TEXT")
    private String options;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** The group this spec belongs to — exactly one, and it drives the display sections. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private SpecGroup group;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * The resolved {@link UnitFamily}, or empty when this field declares none (or declares one this
     * build does not know — a code removed from the enum must not stop the app reading its rows).
     * Empty means the values are never parsed and stay text.
     */
    public Optional<UnitFamily> family() {
        return UnitFamily.byCode(unitFamily);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
