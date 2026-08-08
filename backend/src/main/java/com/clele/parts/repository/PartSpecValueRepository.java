package com.clele.parts.repository;

import com.clele.parts.model.PartSpecValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
