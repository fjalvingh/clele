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
     * matches the part number as a case-insensitive substring and the description via PostgreSQL
     * full-text search (websearch syntax). The category filter matches the given category <em>and
     * all of its descendants at any depth</em> (resolved via a recursive walk of category.parent_id),
     * so picking a higher-level node returns parts in any of its sub-categories. When {@code term}
     * is null only the category filter applies.
     */
    @Query(value = """
            SELECT p.* FROM part p
            WHERE p.organisation_id = :orgId
              AND (:term IS NULL
                   OR p.part_number ILIKE '%' || :term || '%'
                   OR to_tsvector('english', coalesce(p.description, ''))
                      @@ websearch_to_tsquery('english', :term))
              AND (:categoryId IS NULL OR p.category_id IN (
                   WITH RECURSIVE subtree AS (
                       SELECT id FROM category WHERE id = :categoryId
                       UNION ALL
                       SELECT c.id FROM category c JOIN subtree s ON c.parent_id = s.id
                   )
                   SELECT id FROM subtree))
            ORDER BY p.part_number
            """, nativeQuery = true)
    List<Part> search(@Param("orgId") Long organisationId,
                      @Param("term") String term,
                      @Param("categoryId") Long categoryId);

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
}
