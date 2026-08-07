package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A bill of materials imported into a project from a CSV export (KiCad, Eagle, Altium, a
 * distributor tool — the parser is generic).
 *
 * <p>There is exactly <em>one</em> per project (unique {@code project_id}); re-uploading a revised
 * export merges into this row rather than replacing it, so confirmed matches survive a schematic
 * revision. The uploaded bytes are kept so the file that produced the lines can always be
 * downloaded back.
 */
@Entity
@Table(name = "project_bom")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectBom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @Column(name = "filename", length = 255)
    private String filename;

    @Column(name = "content_type", length = 128)
    private String contentType;

    /** The uploaded file, verbatim. */
    @Column(name = "data", columnDefinition = "bytea")
    private byte[] data;

    /**
     * The column mapping used for the last import (role → header name). Remembered so the next
     * upload of the same export pre-fills with what the user already corrected.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping", columnDefinition = "jsonb")
    private Map<String, String> columnMapping;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by_id")
    private AppUser importedBy;
}
