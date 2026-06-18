package com.clele.parts.repository;

import com.clele.parts.model.Part;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long> {

    Optional<Part> findByPartNumber(String partNumber);

    /**
     * Search parts by an optional free-text term and/or category. The term matches the name and
     * part number as a case-insensitive substring, and the description via PostgreSQL full-text
     * search (websearch syntax). The category filter matches the given category <em>and all of its
     * descendants at any depth</em> (resolved via a recursive walk of category.parent_id), so
     * picking a higher-level node returns parts in any of its sub-categories. When {@code term} is
     * null only the category filter applies.
     */
    @Query(value = """
            SELECT p.* FROM part p
            WHERE (:term IS NULL
                   OR p.name ILIKE '%' || :term || '%'
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
            ORDER BY p.name
            """, nativeQuery = true)
    List<Part> search(@Param("term") String term, @Param("categoryId") Long categoryId);

    List<Part> findByCategoryIsNull();

    boolean existsByPartNumber(String partNumber);

    boolean existsByPartNumberAndIdNot(String partNumber, Long id);

    @Query("SELECT COUNT(p) FROM Part p")
    long countAll();
}
