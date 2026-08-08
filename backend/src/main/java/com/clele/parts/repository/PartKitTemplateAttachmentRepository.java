package com.clele.parts.repository;

import com.clele.parts.model.PartKitTemplateAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** The images a kit template hands to every part it generates. */
public interface PartKitTemplateAttachmentRepository extends JpaRepository<PartKitTemplateAttachment, Long> {

    @Query("select k from PartKitTemplateAttachment k join fetch k.attachment "
            + "where k.template.id = :templateId order by k.displayOrder, k.id")
    List<PartKitTemplateAttachment> findByTemplateId(@Param("templateId") Long templateId);

    @Query("select k from PartKitTemplateAttachment k "
            + "where k.template.id = :templateId and k.attachment.id = :attachmentId")
    Optional<PartKitTemplateAttachment> findByTemplateIdAndAttachmentId(
            @Param("templateId") Long templateId, @Param("attachmentId") Long attachmentId);

    int countByTemplateId(Long templateId);

    long countByAttachmentId(Long attachmentId);
}
