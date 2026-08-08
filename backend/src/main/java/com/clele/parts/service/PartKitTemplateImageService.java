package com.clele.parts.service;

import com.clele.parts.dto.PartAttachmentDTO;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.PartAttachment;
import com.clele.parts.model.PartKitTemplate;
import com.clele.parts.model.PartKitTemplateAttachment;
import com.clele.parts.repository.PartKitTemplateAttachmentRepository;
import com.clele.parts.repository.PartKitTemplateRepository;
import com.clele.parts.service.PartAttachmentService.AttachmentContent;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The photos a kit template hands to every part it generates.
 *
 * <p>A kit's parts look the same — thirty resistor values differ in a printed number, not in a
 * photograph — so a template carries the pictures once and every generated part links to the very
 * same {@code part_attachment} row. Nothing is copied at generate time.
 *
 * <p>Storage goes through {@link PartAttachmentService#storeContent}, the same funnel (and the same
 * de-duplication) as a part's own photos: uploading a picture the organisation already holds links
 * it rather than storing a second copy. The template's own images are capped like a part's, because
 * that cap is what they will run into on the generated parts anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartKitTemplateImageService {

    /** Matches the per-part photo cap: a template that offered more could not apply them all. */
    private static final int MAX_IMAGES = 5;

    private final PartKitTemplateRepository templateRepository;
    private final PartKitTemplateAttachmentRepository kitAttachmentRepository;
    private final PartAttachmentService partAttachmentService;
    private final CurrentOrganisationService currentOrganisationService;

    public List<PartAttachmentDTO> list(Long templateId) {
        require(templateId);
        return kitAttachmentRepository.findByTemplateId(templateId).stream()
                .map(PartKitTemplateImageService::toDTO)
                .toList();
    }

    public AttachmentContent getContent(Long templateId, Long attachmentId) {
        require(templateId);
        PartAttachment a = kitAttachmentRepository
                .findByTemplateIdAndAttachmentId(templateId, attachmentId)
                .orElseThrow(() -> new EntityNotFoundException("Image not found: " + attachmentId))
                .getAttachment();
        return new AttachmentContent(a.getData(), a.getContentType(), a.getFilename());
    }

    @Transactional
    public PartAttachmentDTO upload(Long templateId, MultipartFile file) {
        PartKitTemplate template = require(templateId);
        return attach(template, partAttachmentService.storeContent(template.getOrganisation(),
                partAttachmentService.convertToPng(file), MediaType.IMAGE_PNG_VALUE,
                null, AttachmentType.PHOTO, template.getName()));
    }

    /**
     * Take an image off the template. The parts already generated keep theirs — they hold their own
     * links, and a photo removed from the recipe is not a photo removed from what it baked.
     */
    @Transactional
    public void delete(Long templateId, Long attachmentId) {
        require(templateId);
        PartKitTemplateAttachment row = kitAttachmentRepository
                .findByTemplateIdAndAttachmentId(templateId, attachmentId)
                .orElseThrow(() -> new EntityNotFoundException("Image not found: " + attachmentId));
        PartAttachment attachment = row.getAttachment();

        kitAttachmentRepository.delete(row);
        kitAttachmentRepository.flush();
        partAttachmentService.deleteIfUnused(attachment);

        List<PartKitTemplateAttachment> remaining = kitAttachmentRepository.findByTemplateId(templateId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setDisplayOrder(i);
        }
        kitAttachmentRepository.saveAll(remaining);
    }

    private PartAttachmentDTO attach(PartKitTemplate template, PartAttachment attachment) {
        PartKitTemplateAttachment existing = kitAttachmentRepository
                .findByTemplateIdAndAttachmentId(template.getId(), attachment.getId()).orElse(null);
        if (existing != null) {
            return toDTO(existing);
        }
        if (kitAttachmentRepository.countByTemplateId(template.getId()) >= MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum of " + MAX_IMAGES + " images per kit template");
        }
        return toDTO(kitAttachmentRepository.save(PartKitTemplateAttachment.builder()
                .template(template)
                .attachment(attachment)
                .displayOrder(kitAttachmentRepository.countByTemplateId(template.getId()))
                .build()));
    }

    private PartKitTemplate require(Long templateId) {
        return templateRepository
                .findByIdAndOrganisationId(templateId, currentOrganisationService.currentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kit template not found: " + templateId));
    }

    /**
     * Reuses {@code PartAttachmentDTO} with no {@code partId}: a template image is the same content
     * in the same store, it just has no part behind it yet.
     */
    private static PartAttachmentDTO toDTO(PartKitTemplateAttachment k) {
        PartAttachment a = k.getAttachment();
        return PartAttachmentDTO.builder()
                .id(a.getId())
                .type(a.getType())
                .displayOrder(k.getDisplayOrder())
                .contentType(a.getContentType())
                .filename(a.getFilename())
                .description(a.getDescription())
                .md5Hash(a.getMd5Hash())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
