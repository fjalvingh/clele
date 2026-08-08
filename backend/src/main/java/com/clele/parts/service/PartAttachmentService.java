package com.clele.parts.service;

import com.clele.parts.dto.PartAttachmentDTO;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartAttachment;
import com.clele.parts.model.PartAttachmentLink;
import com.clele.parts.repository.PartAttachmentLinkRepository;
import com.clele.parts.repository.PartAttachmentRepository;
import com.clele.parts.repository.PartKitTemplateAttachmentRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.util.PdfBytes;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Photos, datasheets and files held against parts.
 *
 * <p><b>Attachments are shared.</b> The bytes live in one {@code part_attachment} row and each part
 * that shows them holds a {@code part_attachment_link} — so the thirty values of a resistor kit can
 * carry the identical photo without thirty copies of it. Every write goes through
 * {@link #store(Long, byte[], String, String, AttachmentType)}, which is the only place that decides
 * whether bytes are new: content already held for the organisation with the same MD5 and type is
 * linked rather than stored again.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PartAttachmentService {

    private static final int MAX_PHOTOS = 5;
    private static final MediaType PNG = MediaType.IMAGE_PNG;

    private final PartAttachmentRepository partAttachmentRepository;
    private final PartAttachmentLinkRepository partAttachmentLinkRepository;
    private final PartKitTemplateAttachmentRepository partKitTemplateAttachmentRepository;
    private final PartRepository partRepository;

    /**
     * The Apache HttpClient-backed template, <b>not</b> the default one.
     *
     * <p>The default {@code restTemplate} uses {@code SimpleClientHttpRequestFactory}, whose
     * {@code HttpURLConnection} silently refuses to follow a redirect that changes protocol. Most
     * stored datasheet URLs are {@code http://} links that redirect to {@code https://}, and on the
     * default factory those come back as HTTP 200 carrying the redirect interstitial's HTML — which
     * looks exactly like a successful download of a non-PDF. That cost a whole datasheet-preflight
     * run before it was spotted; {@code DatasheetBackfillService} has the same qualifier for the
     * same reason.
     *
     * <p>Written out as an explicit constructor field rather than relying on Lombok, because
     * {@code @RequiredArgsConstructor} does not copy the qualifier onto the generated parameter and
     * the wrong bean would be injected silently.
     */
    private final RestTemplate restTemplate;

    public PartAttachmentService(PartAttachmentRepository partAttachmentRepository,
                                 PartAttachmentLinkRepository partAttachmentLinkRepository,
                                 PartKitTemplateAttachmentRepository partKitTemplateAttachmentRepository,
                                 PartRepository partRepository,
                                 @Qualifier("datasheetRestTemplate") RestTemplate restTemplate) {
        this.partAttachmentRepository = partAttachmentRepository;
        this.partAttachmentLinkRepository = partAttachmentLinkRepository;
        this.partKitTemplateAttachmentRepository = partKitTemplateAttachmentRepository;
        this.partRepository = partRepository;
        this.restTemplate = restTemplate;
    }

    /** Raw bytes plus the headers needed to serve them. */
    public record AttachmentContent(byte[] data, String contentType, String filename) {}

    public List<PartAttachmentDTO> list(Long partId, AttachmentType type) {
        List<PartAttachmentLink> links = (type == null)
                ? partAttachmentLinkRepository.findByPartId(partId)
                : partAttachmentLinkRepository.findByPartIdAndType(partId, type);
        Map<Long, Integer> counts = partCounts(links);
        return links.stream().map(l -> toDTO(l, counts)).toList();
    }

    /**
     * How many parts use each listed attachment, in one query — the figure the UI needs to say that
     * removing a shared photo here leaves the other parts alone.
     */
    private Map<Long, Integer> partCounts(List<PartAttachmentLink> links) {
        if (links.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = links.stream().map(l -> l.getAttachment().getId()).toList();
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : partAttachmentLinkRepository.countPartsByAttachmentIds(ids)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    public AttachmentContent getContent(Long partId, Long attachmentId) {
        PartAttachment a = partAttachmentRepository.findByIdAndPartId(attachmentId, partId)
                .orElseThrow(() -> new EntityNotFoundException("Attachment not found: " + attachmentId));
        return new AttachmentContent(a.getData(), a.getContentType(), a.getFilename());
    }

    @Transactional
    public PartAttachmentDTO upload(Long partId, MultipartFile file, AttachmentType type) {
        requirePart(partId);

        if (type == AttachmentType.PHOTO) {
            return store(partId, convertToPng(file), PNG.toString(), null, type);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read file: " + e.getMessage());
        }
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        return store(partId, bytes,
                orDefault(file.getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE),
                file.getOriginalFilename(), type);
    }

    @Transactional
    public PartAttachmentDTO uploadFromUrl(Long partId, String url, AttachmentType type) {
        requirePart(partId);

        if (type == AttachmentType.PHOTO) {
            return store(partId, downloadAndConvertToPng(url), PNG.toString(), null, type);
        }

        Downloaded d = download(url);
        // A datasheet must actually be a PDF. Vendors answer a moved or retired document with
        // HTTP 200 and an HTML landing page rather than a 404, and the URL ending in .pdf says
        // nothing about what came back — stored unchecked, that page becomes a "datasheet" that
        // only reveals itself when somebody opens it. Only DATASHEET is checked; a general
        // ATTACHMENT is whatever the user says it is.
        if (type == AttachmentType.DATASHEET && !PdfBytes.looksLikePdf(d.bytes())) {
            log.warn("Refusing datasheet from {}: not a PDF (content-type {}, {} bytes)",
                    url, d.contentType(), d.bytes().length);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That URL did not return a PDF — the vendor most likely served a web page "
                            + "instead of the document. Open it in a browser to check.");
        }
        return store(partId, d.bytes(), d.contentType(), filenameFromUrl(url), type);
    }

    /**
     * Attach content to a part — the single write path, and the only place that decides whether
     * bytes are new.
     *
     * <p>Content the organisation already holds with the same MD5 and type is <b>linked, not
     * stored again</b>: a photo shared by every value of a resistor kit exists once. A hit keeps
     * its original {@code description}, {@code filename} and content type, since those record where
     * the file first came from and the second part adds nothing to that.
     *
     * <p>Takes a part <em>id</em> rather than the entity because the datasheet backfill runs
     * outside a transaction with detached parts, and reading {@code part.getOrganisation()} off one
     * of those would fail lazily.
     */
    @Transactional
    public PartAttachmentDTO store(Long partId, byte[] data, String contentType, String filename,
                                   AttachmentType type) {
        Part part = requirePart(partId);
        PartAttachment held = findIdentical(part.getOrganisation().getId(), data, type);

        if (held != null) {
            // Already on this part: nothing to add, and re-linking would violate the unique key.
            PartAttachmentLink existing = partAttachmentLinkRepository
                    .findByPartIdAndAttachmentId(partId, held.getId()).orElse(null);
            if (existing != null) {
                return toDTO(existing, usageOf(held));
            }
            log.info("Reusing attachment {} ({}) for part {} — identical content already stored",
                    held.getId(), type, partId);
        }

        PartAttachment attachment = (held != null) ? held
                : createContent(part.getOrganisation(), data, contentType, filename, type,
                        part.getPartNumber());
        return toDTO(link(part, attachment), usageOf(attachment));
    }

    /**
     * Store content with no part behind it yet — what a kit template's images are, since the parts
     * they belong to do not exist until the kit is generated. Same de-duplication as
     * {@link #store}: identical bytes already held by the organisation are returned as they are.
     *
     * <p>{@code description} names the origin, which for a template is the kit rather than a part
     * number. That is the same promise the column makes everywhere — where this content came from,
     * fixed at creation.
     */
    @Transactional
    public PartAttachment storeContent(Organisation organisation, byte[] data, String contentType,
                                       String filename, AttachmentType type, String description) {
        PartAttachment held = findIdentical(organisation.getId(), data, type);
        return held != null ? held
                : createContent(organisation, data, contentType, filename, type, description);
    }

    /**
     * Link content the caller already holds to a part, honouring the photo cap and the part's own
     * ordering. Used when generating a kit, where every part gets the same pictures. A part already
     * linked to it is left alone rather than failing on the unique key — generating the same kit
     * twice must be a no-op for images, as it is for the part's fields.
     */
    @Transactional
    public void link(Long partId, PartAttachment attachment) {
        Part part = requirePart(partId);
        if (partAttachmentLinkRepository.findByPartIdAndAttachmentId(partId, attachment.getId()).isPresent()) {
            return;
        }
        link(part, attachment);
    }

    /** The bytes this organisation already holds, or null. */
    private PartAttachment findIdentical(Long organisationId, byte[] data, AttachmentType type) {
        // The hash narrows it down; the bytes decide. An MD5 collision is unlikely and silently
        // serving another part's document would be the kind of wrong that never gets noticed.
        return partAttachmentRepository
                .findByOrganisationIdAndMd5HashAndType(organisationId, md5(data), type)
                .stream()
                .filter(a -> Arrays.equals(a.getData(), data))
                .findFirst()
                .orElse(null);
    }

    private PartAttachment createContent(Organisation organisation, byte[] data, String contentType,
                                         String filename, AttachmentType type, String description) {
        return partAttachmentRepository.save(PartAttachment.builder()
                .organisation(organisation)
                .type(type)
                .data(data)
                .contentType(contentType)
                .filename(filename)
                .description(truncate(description, 255))
                .md5Hash(md5(data))
                .build());
    }

    private PartAttachmentLink link(Part part, PartAttachment attachment) {
        if (attachment.getType() == AttachmentType.PHOTO) {
            enforcePhotoLimit(part.getId());
        }
        return partAttachmentLinkRepository.save(PartAttachmentLink.builder()
                .part(part)
                .attachment(attachment)
                .displayOrder(nextDisplayOrder(part.getId(), attachment.getType()))
                .build());
    }

    /** The one-entry count map {@link #toDTO} needs when a single attachment is being reported. */
    private Map<Long, Integer> usageOf(PartAttachment attachment) {
        partAttachmentLinkRepository.flush();
        return Map.of(attachment.getId(),
                (int) partAttachmentLinkRepository.countByAttachmentId(attachment.getId()));
    }

    /**
     * Remove an attachment <b>from this part</b>. Shared content stays put for the parts still
     * using it; the bytes go only when the last part lets go of them.
     */
    @Transactional
    public void delete(Long partId, Long attachmentId) {
        PartAttachmentLink link = partAttachmentLinkRepository
                .findByPartIdAndAttachmentId(partId, attachmentId)
                .orElseThrow(() -> new EntityNotFoundException("Attachment not found: " + attachmentId));
        AttachmentType type = link.getAttachment().getType();
        PartAttachment attachment = link.getAttachment();

        partAttachmentLinkRepository.delete(link);
        partAttachmentLinkRepository.flush();
        deleteIfUnused(attachment);

        // Re-sequence display_order within the same type so it stays 0-based and contiguous.
        List<PartAttachmentLink> remaining = partAttachmentLinkRepository.findByPartIdAndType(partId, type);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setDisplayOrder(i);
        }
        partAttachmentLinkRepository.saveAll(remaining);
    }

    /**
     * Detach everything from a part that is being deleted, dropping the content no other part uses.
     * Bulk paths that delete parts straight through the database rely on the {@code ON DELETE
     * CASCADE} on the link instead, and must call {@link #deleteOrphans()} afterwards.
     */
    @Transactional
    public void deleteAllForPart(Long partId) {
        partAttachmentLinkRepository.deleteByPartId(partId);
        partAttachmentLinkRepository.flush();
        partAttachmentRepository.deleteOrphans();
    }

    /**
     * Drop the content if nothing points at it any more — no part, and no kit template. A template's
     * images have no part behind them until the kit is generated, so parts alone are not the whole
     * picture.
     */
    @Transactional
    public void deleteIfUnused(PartAttachment attachment) {
        if (partAttachmentLinkRepository.countByAttachmentId(attachment.getId()) == 0
                && partKitTemplateAttachmentRepository.countByAttachmentId(attachment.getId()) == 0) {
            partAttachmentRepository.delete(attachment);
        }
    }

    /** Drop content nothing links to any more; returns how many rows went. */
    @Transactional
    public int deleteOrphans() {
        return partAttachmentRepository.deleteOrphans();
    }

    private Part requirePart(Long partId) {
        return partRepository.findById(partId)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + partId));
    }

    private int nextDisplayOrder(Long partId, AttachmentType type) {
        return partAttachmentLinkRepository.countByPartIdAndType(partId, type);
    }

    private void enforcePhotoLimit(Long partId) {
        if (partAttachmentLinkRepository.countByPartIdAndType(partId, AttachmentType.PHOTO) >= MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum of " + MAX_PHOTOS + " photos per part");
        }
    }

    /** Hex MD5 of the stored bytes — the fingerprint an identical upload is recognised by. */
    private static String md5(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String truncate(String value, int max) {
        return (value != null && value.length() > max) ? value.substring(0, max) : value;
    }

    private record Downloaded(byte[] bytes, String contentType) {}

    /** Download raw bytes from an external URL (SSRF-guarded), preserving the response content-type. */
    private Downloaded download(String url) {
        log.info("Downloading attachment from URL: {}", url);
        var uri = com.clele.parts.util.UrlSafety.validateExternalHttpUrl(url);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
            headers.set("Accept", "*/*");
            headers.set("Accept-Language", "en-US,en;q=0.5");
            headers.set("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty response from URL");
            }
            MediaType ct = response.getHeaders().getContentType();
            return new Downloaded(body, ct != null ? ct.toString() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Failed to download from {}: {}", url, e.getStatusCode());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The source refused the download (HTTP " + e.getStatusCode().value()
                            + "). It may be blocking automated requests — try opening the URL in a browser instead.");
        } catch (Exception e) {
            log.error("Failed to download from {}: {}", url, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to download: " + e.getMessage());
        }
    }

    /**
     * Fetch an external image and normalize it to PNG. Public because a kit template's images take
     * the same route without a part to hang them on.
     */
    public byte[] downloadAndConvertToPng(String url) {
        Downloaded d = download(url);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(d.bytes()));
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL did not return a valid image");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to process image: " + e.getMessage());
        }
    }

    /** Normalize an uploaded image to PNG. Public for the same reason as {@link #downloadAndConvertToPng}. */
    public byte[] convertToPng(MultipartFile file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read image: " + e.getMessage());
        }
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or invalid image file");
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to encode image as PNG: " + e.getMessage());
        }
    }

    /** Best-effort original filename from a URL path; falls back to a generic name. */
    private String filenameFromUrl(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            if (path != null) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return "download";
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private PartAttachmentDTO toDTO(PartAttachmentLink link, Map<Long, Integer> counts) {
        PartAttachment a = link.getAttachment();
        return PartAttachmentDTO.builder()
                .id(a.getId())
                .partId(link.getPart().getId())
                .type(a.getType())
                .displayOrder(link.getDisplayOrder())
                .contentType(a.getContentType())
                .filename(a.getFilename())
                .description(a.getDescription())
                .md5Hash(a.getMd5Hash())
                .partCount(counts.getOrDefault(a.getId(), 1))
                .createdAt(a.getCreatedAt())
                .build();
    }
}
