package com.clele.parts.repository;

import com.clele.parts.model.Part;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long> {

    Optional<Part> findByOrganisationIdAndPartNumber(Long organisationId, String partNumber);

    Optional<Part> findByIdAndOrganisationId(Long id, Long organisationId);

    /**
     * Search parts by an optional free-text term and/or category, within one organisation. The term
     * matches the part number as a case-insensitive substring, and the description, {@code details}
     * and the string values in {@code specs} via PostgreSQL full-text search (websearch syntax) —
     * so "sot-23" or "0805" finds a part by its package, which is how a part is usually
     * remembered. The three are concatenated into a single tsvector, not matched separately and
     * OR'd: a tsquery ANDs its terms, so "transistor sot-23" must find both terms in one vector.
     * The expression is indexed verbatim by V43 ({@code idx_part_search_fts}) — editing one side
     * without the other silently costs the index, not correctness.
     *
     * <p>The category filter matches the given category <em>and
     * all of its descendants at any depth</em> (resolved via a recursive walk of category.parent_id),
     * so picking a higher-level node returns parts in any of its sub-categories.
     *
     * <p>The remaining parameters are the Parts screen's advanced filters; every one of them is
     * optional and ignored when null. {@code personalNumber} matches the flag exactly;
     * {@code manufacturer} is a case-insensitive substring; {@code locationId} keeps parts that
     * hold stock in that location <em>or any location below it</em> (same recursive walk as
     * categories, so filtering on "Building A" finds stock on a shelf three levels down). Tag
     * filtering is not done here — see {@code PartService.search}.
     */
    @Query(value = """
            SELECT p.* FROM part p
            WHERE p.organisation_id = :orgId
              AND (:term IS NULL
                   OR p.part_number ILIKE '%' || :term || '%'
                   OR (to_tsvector('english', coalesce(p.description, ''))
                       || to_tsvector('english', coalesce(p.details, ''))
                       || jsonb_to_tsvector('english', coalesce(p.specs, '{}'), '["string"]'))
                      @@ websearch_to_tsquery('english', :term))
              AND (:categoryId IS NULL OR p.category_id IN (
                   WITH RECURSIVE subtree AS (
                       SELECT id FROM category WHERE id = :categoryId
                       UNION ALL
                       SELECT c.id FROM category c JOIN subtree s ON c.parent_id = s.id
                   )
                   SELECT id FROM subtree))
              AND (:personalNumber IS NULL OR p.personal_number = :personalNumber)
              AND (:manufacturer IS NULL OR p.manufacturer ILIKE '%' || :manufacturer || '%')
              AND (:locationId IS NULL OR EXISTS (
                   SELECT 1 FROM stock_entry se
                   WHERE se.part_id = p.id
                     AND se.quantity > 0
                     AND se.location_id IN (
                         WITH RECURSIVE loctree AS (
                             SELECT id FROM location WHERE id = :locationId
                             UNION ALL
                             SELECT l.id FROM location l JOIN loctree lt ON l.parent_id = lt.id
                         )
                         SELECT id FROM loctree)))
            ORDER BY p.part_number
            """, nativeQuery = true)
    List<Part> search(@Param("orgId") Long organisationId,
                      @Param("term") String term,
                      @Param("categoryId") Long categoryId,
                      @Param("personalNumber") Boolean personalNumber,
                      @Param("manufacturer") String manufacturer,
                      @Param("locationId") Long locationId);

    /**
     * Fuzzy-match existing parts by part number within one organisation, for Quick Add's "do we
     * already have this?" check. Returns parts whose part_number is trigram-similar to the term
     * (typo/transposition tolerant via pg_trgm's {@code %} operator) or contains it as a
     * case-insensitive substring, best match first.
     */
    @Query(value = """
            SELECT p.* FROM part p
            WHERE p.organisation_id = :orgId
              AND (p.part_number % :term
                   OR p.part_number ILIKE '%' || :term || '%')
            ORDER BY similarity(p.part_number, :term) DESC, p.part_number
            LIMIT 10
            """, nativeQuery = true)
    List<Part> fuzzyByPartNumber(@Param("orgId") Long organisationId, @Param("term") String term);

    List<Part> findByOrganisationId(Long organisationId);

    List<Part> findByOrganisationIdAndCategoryIsNull(Long organisationId);

    /** Ids of every part created by the given user in the given organisation (for bulk cleanup). */
    @Query("SELECT p.id FROM Part p WHERE p.createdBy.id = :userId AND p.organisation.id = :orgId")
    List<Long> findIdsByCreatedByIdAndOrganisationId(@Param("userId") Long userId,
                                                     @Param("orgId") Long organisationId);

    /**
     * Bulk-delete the given parts. DB-level ON DELETE CASCADE removes the dependent part_attachment
     * and stock_movement rows; stock_entry (no cascade) must be cleared first. Returns the number
     * of parts deleted.
     */
    @Modifying
    @Query("DELETE FROM Part p WHERE p.id IN :partIds")
    int deleteByIdIn(@Param("partIds") List<Long> partIds);

    boolean existsByOrganisationIdAndPartNumber(Long organisationId, String partNumber);

    boolean existsByOrganisationIdAndPartNumberAndIdNot(Long organisationId, String partNumber, Long id);

    long countByOrganisationId(Long organisationId);

    /**
     * Parts carrying a datasheet URL that has not been downloaded into {@code part_attachment} yet,
     * across every organisation — this drives the datasheet preflight/backfill CLI, which is a
     * maintenance tool rather than a per-tenant request. Ordered by id so a capped run
     * ({@code --datasheets.limit}) samples deterministically and a resumed run picks up where the
     * previous one stopped.
     */
    @Query("""
            SELECT p FROM Part p
            WHERE p.datasheetUrl IS NOT NULL AND TRIM(p.datasheetUrl) <> ''
              AND NOT EXISTS (SELECT 1 FROM PartAttachment a
                              WHERE a.part = p AND a.type = com.clele.parts.model.AttachmentType.DATASHEET)
            ORDER BY p.id
            """)
    List<Part> findWithUndownloadedDatasheet();
}
