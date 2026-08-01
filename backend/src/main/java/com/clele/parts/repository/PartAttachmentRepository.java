package com.clele.parts.repository;

import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.PartAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartAttachmentRepository extends JpaRepository<PartAttachment, Long> {

    List<PartAttachment> findByPartIdOrderByDisplayOrder(Long partId);

    List<PartAttachment> findByPartIdAndTypeOrderByDisplayOrder(Long partId, AttachmentType type);

    int countByPartIdAndType(Long partId, AttachmentType type);

    Optional<PartAttachment> findByIdAndPartId(Long id, Long partId);

    void deleteByPartId(Long partId);

    /**
     * Attachment ids of the given type for a batch of parts, as {@code [partId, attachmentId]} rows
     * ordered so the first row per part is its first attachment. Selects ids only — the {@code data}
     * bytea is never loaded. Used to give list rows a thumbnail without an N+1 query.
     */
    @Query("select a.part.id, a.id from PartAttachment a "
            + "where a.part.id in :partIds and a.type = :type "
            + "order by a.part.id, a.displayOrder, a.id")
    List<Object[]> findIdsByPartIdsAndType(@Param("partIds") List<Long> partIds,
                                           @Param("type") AttachmentType type);
}
