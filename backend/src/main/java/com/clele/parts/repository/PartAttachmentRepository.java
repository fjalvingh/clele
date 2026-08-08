package com.clele.parts.repository;

import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.PartAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * The attachment <em>content</em>. Which parts use a row lives in {@code part_attachment_link}, so
 * every per-part query here reaches it through {@link PartAttachmentLinkRepository}'s table.
 */
public interface PartAttachmentRepository extends JpaRepository<PartAttachment, Long> {

    @Query("select l.attachment from PartAttachmentLink l "
            + "where l.part.id = :partId and l.attachment.type = :type "
            + "order by l.displayOrder, l.attachment.id")
    List<PartAttachment> findByPartIdAndTypeOrderByDisplayOrder(@Param("partId") Long partId,
                                                                @Param("type") AttachmentType type);

    /**
     * An attachment as reached from one part — the link is what authorises the part-scoped URL, so
     * an attachment this part does not use is simply not found.
     */
    @Query("select l.attachment from PartAttachmentLink l "
            + "where l.part.id = :partId and l.attachment.id = :id")
    Optional<PartAttachment> findByIdAndPartId(@Param("id") Long id, @Param("partId") Long partId);

    /**
     * Content already held for this organisation with exactly these bytes, so an upload that
     * duplicates one can link the existing row instead of storing it again. Matched on type too: a
     * PDF stored as a datasheet and the same PDF stored as a user attachment are shown in different
     * places and deleted independently.
     */
    List<PartAttachment> findByOrganisationIdAndMd5HashAndType(Long organisationId, String md5Hash,
                                                               AttachmentType type);

    /**
     * Drop content no part links to any more. Deleting a part removes its links by DB cascade but
     * cannot know whether the attachment survived elsewhere, so the sweep runs after any path that
     * removes parts or links in bulk.
     */
    @Modifying
    @Query("delete from PartAttachment a where not exists "
            + "(select 1 from PartAttachmentLink l where l.attachment = a)")
    int deleteOrphans();
}
