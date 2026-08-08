package com.clele.parts.repository;

import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.PartAttachmentLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Which parts use which attachments, and in what order each part shows them. */
public interface PartAttachmentLinkRepository extends JpaRepository<PartAttachmentLink, Long> {

    @Query("select l from PartAttachmentLink l join fetch l.attachment a "
            + "where l.part.id = :partId order by a.type, l.displayOrder, a.id")
    List<PartAttachmentLink> findByPartId(@Param("partId") Long partId);

    @Query("select l from PartAttachmentLink l join fetch l.attachment a "
            + "where l.part.id = :partId and a.type = :type order by l.displayOrder, a.id")
    List<PartAttachmentLink> findByPartIdAndType(@Param("partId") Long partId,
                                                 @Param("type") AttachmentType type);

    @Query("select count(l) from PartAttachmentLink l "
            + "where l.part.id = :partId and l.attachment.type = :type")
    int countByPartIdAndType(@Param("partId") Long partId, @Param("type") AttachmentType type);

    @Query("select l from PartAttachmentLink l "
            + "where l.part.id = :partId and l.attachment.id = :attachmentId")
    Optional<PartAttachmentLink> findByPartIdAndAttachmentId(@Param("partId") Long partId,
                                                             @Param("attachmentId") Long attachmentId);

    long countByAttachmentId(Long attachmentId);

    void deleteByPartId(Long partId);

    /** How many parts use each of the given attachments, as {@code [attachmentId, count]} rows. */
    @Query("select l.attachment.id, count(l) from PartAttachmentLink l "
            + "where l.attachment.id in :attachmentIds group by l.attachment.id")
    List<Object[]> countPartsByAttachmentIds(@Param("attachmentIds") List<Long> attachmentIds);

    /**
     * Attachment ids of the given type for a batch of parts, as {@code [partId, attachmentId]} rows
     * ordered so the first row per part is its first attachment. Selects ids only — the {@code data}
     * bytea is never loaded. Used to give list rows a thumbnail without an N+1 query.
     */
    @Query("select l.part.id, l.attachment.id from PartAttachmentLink l "
            + "where l.part.id in :partIds and l.attachment.type = :type "
            + "order by l.part.id, l.displayOrder, l.attachment.id")
    List<Object[]> findIdsByPartIdsAndType(@Param("partIds") List<Long> partIds,
                                           @Param("type") AttachmentType type);
}
