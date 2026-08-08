package com.clele.parts.repository;

import com.clele.parts.model.PartSpecValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface PartSpecValueRepository extends JpaRepository<PartSpecValue, PartSpecValue.Key> {

    List<PartSpecValue> findByPartId(Long partId);

    /** Every value of a set of parts, for assembling many DTOs without a query per part. */
    @Query("select v from PartSpecValue v join fetch v.specDefinition where v.part.id in :partIds")
    List<PartSpecValue> findByPartIdIn(@Param("partIds") Collection<Long> partIds);

    @Modifying
    void deleteByPartId(Long partId);

    @Modifying
    @Query("delete from PartSpecValue v where v.part.id = :partId and v.specDefinition.id in :specIds")
    void deleteByPartIdAndSpecDefinitionIdIn(@Param("partId") Long partId,
                                             @Param("specIds") Collection<Long> specIds);

    /**
     * Bulk cleanup for the paths that delete parts straight through the database. The FK cascades,
     * so this is only needed where rows are removed without loading the parts.
     */
    @Modifying
    @Query("delete from PartSpecValue v where v.part.id in :partIds")
    void deleteByPartIdIn(@Param("partIds") Collection<Long> partIds);

    /**
     * Parts satisfying one numeric spec criterion — the query a parts database exists for
     * ("Vds ≥ 60 V", "resistance = 4.7 kΩ"). One indexed {@code EXISTS} per criterion; the caller
     * intersects.
     *
     * <h2>Interval semantics</h2>
     *
     * A value is either a scalar or a range, and the predicate asks whether the part <em>has some
     * value satisfying it</em> — so a range answers on the bound that could satisfy it:
     * {@code ≥ 60} is true of {@code 4..70} because 70 is, and {@code = 3.3} is true of
     * {@code 2..5.5} because the range covers it. That is what makes "supply voltage = 3.3 V" find
     * the parts that can actually run at 3.3 V rather than only those whose value is written 3.3.
     *
     * <p>An open bound means unbounded, so it satisfies any comparison in its direction — spelled as
     * an explicit {@code IS NULL} rather than by coalescing to an infinity, both because
     * {@code 'Infinity'::numeric} cannot be written in a Hibernate native query ({@code ::} starts a
     * named parameter) and because coalescing to the compared value gets {@code >} wrong: an
     * unbounded upper bound does exceed the value, but {@code x > x} is false.
     *
     * <p>The range test is guarded by "this row is a range". Without it a text row — whose bounds
     * are both NULL — would satisfy every comparison.
     */
    @Query(value = """
            SELECT v.part_id FROM part_spec_value v
            JOIN spec_definition sd ON sd.id = v.spec_definition_id
            WHERE sd.organisation_id = :orgId
              AND sd.json_name = :jsonName
              AND (
                (:op = 'eq'  AND (v.value_num = :num
                     OR ((v.value_min IS NOT NULL OR v.value_max IS NOT NULL)
                         AND (v.value_min IS NULL OR v.value_min <= :num)
                         AND (v.value_max IS NULL OR v.value_max >= :num))))
             OR (:op = 'gte' AND (v.value_num >= :num
                     OR ((v.value_min IS NOT NULL OR v.value_max IS NOT NULL)
                         AND (v.value_max IS NULL OR v.value_max >= :num))))
             OR (:op = 'gt'  AND (v.value_num > :num
                     OR ((v.value_min IS NOT NULL OR v.value_max IS NOT NULL)
                         AND (v.value_max IS NULL OR v.value_max > :num))))
             OR (:op = 'lte' AND (v.value_num <= :num
                     OR ((v.value_min IS NOT NULL OR v.value_max IS NOT NULL)
                         AND (v.value_min IS NULL OR v.value_min <= :num))))
             OR (:op = 'lt'  AND (v.value_num < :num
                     OR ((v.value_min IS NOT NULL OR v.value_max IS NOT NULL)
                         AND (v.value_min IS NULL OR v.value_min < :num))))
              )
            """, nativeQuery = true)
    List<Long> partIdsMatchingNumeric(@Param("orgId") Long organisationId,
                                      @Param("jsonName") String jsonName,
                                      @Param("op") String op,
                                      @Param("num") BigDecimal num);

    /** Parts satisfying one textual spec criterion — exact (case-insensitive) or substring. */
    @Query(value = """
            SELECT v.part_id FROM part_spec_value v
            JOIN spec_definition sd ON sd.id = v.spec_definition_id
            WHERE sd.organisation_id = :orgId
              AND sd.json_name = :jsonName
              AND v.value_text IS NOT NULL
              AND ((:op = 'eq' AND lower(v.value_text) = lower(:text))
                OR (:op = 'contains' AND v.value_text ILIKE '%' || :text || '%'))
            """, nativeQuery = true)
    List<Long> partIdsMatchingText(@Param("orgId") Long organisationId,
                                   @Param("jsonName") String jsonName,
                                   @Param("op") String op,
                                   @Param("text") String text);

    /** Parts carrying any value at all for a spec — the "has this field" criterion. */
    @Query(value = """
            SELECT v.part_id FROM part_spec_value v
            JOIN spec_definition sd ON sd.id = v.spec_definition_id
            WHERE sd.organisation_id = :orgId AND sd.json_name = :jsonName
            """, nativeQuery = true)
    List<Long> partIdsWithSpec(@Param("orgId") Long organisationId,
                               @Param("jsonName") String jsonName);
}
