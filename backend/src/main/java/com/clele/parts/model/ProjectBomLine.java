package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * One line of an imported {@link ProjectBom} — a row of the uploaded file, plus the match decision
 * made about it.
 *
 * <p>The line keeps what the file said (designators, value, footprint, MPN, quantity) separately
 * from what the user concluded (status, part, notes). A re-import refreshes the former and
 * preserves the latter.
 */
@Entity
@Table(name = "project_bom_line", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"bom_id", "reference_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectBomLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bom_id", nullable = false)
    private ProjectBom bom;

    /** Position in the uploaded file, 1-based — the order the screen lists lines in. */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    /**
     * The merge key: this line's designators normalised (uppercased, split on comma or whitespace,
     * naturally sorted, rejoined with ","). Pairing a re-imported file against the stored BOM on
     * this key is what carries a confirmed match across a revision. A file with no designator
     * column falls back to a key built from mpn/value/line number.
     */
    @Column(name = "reference_key", nullable = false, length = 512)
    private String referenceKey;

    /** The designators as the file wrote them, for display: "C1, C2, C3". */
    @Column(name = "designators", columnDefinition = "TEXT")
    private String designators;

    @Column(name = "value", length = 255)
    private String value;

    @Column(name = "footprint", length = 255)
    private String footprint;

    @Column(name = "mpn", length = 128)
    private String mpn;

    @Column(name = "manufacturer", length = 255)
    private String manufacturer;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "datasheet_url", columnDefinition = "TEXT")
    private String datasheetUrl;

    /** Quantity per build instance (per board), multiplied by {@code project.instanceCount}. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** The file's do-not-populate flag; lands the line on {@link BomLineStatus#EXCLUDED}. */
    @Column(name = "dnp", nullable = false)
    private boolean dnp;

    /** Columns the mapping did not claim, kept verbatim so nothing in the file is lost. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", columnDefinition = "jsonb")
    private Map<String, String> extra;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BomLineStatus status;

    /** Null while unmatched. {@link BomMatchSource#MANUAL} is never overwritten by a re-import. */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_source", length = 20)
    private BomMatchSource matchSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id")
    private Part part;

    /**
     * The last merge kept this line's match but found its value or footprint had moved — the part
     * may no longer be the right one. Flags the line for review; cleared once the user confirms.
     */
    @Column(name = "changed", nullable = false)
    private boolean changed;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * The line's effective status. A part deleted from the catalogue leaves {@code part_id} NULL
     * (ON DELETE SET NULL) on a row still reading MATCHED; report that as unmatched rather than
     * writing to the database on a read.
     */
    public BomLineStatus effectiveStatus() {
        return status == BomLineStatus.MATCHED && part == null ? BomLineStatus.UNMATCHED : status;
    }
}
