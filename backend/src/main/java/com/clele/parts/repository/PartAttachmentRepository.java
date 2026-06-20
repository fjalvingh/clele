package com.clele.parts.repository;

import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.PartAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartAttachmentRepository extends JpaRepository<PartAttachment, Long> {

    List<PartAttachment> findByPartIdOrderByDisplayOrder(Long partId);

    List<PartAttachment> findByPartIdAndTypeOrderByDisplayOrder(Long partId, AttachmentType type);

    int countByPartIdAndType(Long partId, AttachmentType type);

    Optional<PartAttachment> findByIdAndPartId(Long id, Long partId);

    void deleteByPartId(Long partId);
}
