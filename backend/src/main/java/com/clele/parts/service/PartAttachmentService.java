package com.clele.parts.service;

import com.clele.parts.dto.PartAttachmentDTO;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartAttachment;
import com.clele.parts.repository.PartAttachmentRepository;
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
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class PartAttachmentService {

    private static final int MAX_PHOTOS = 5;
    private static final MediaType PNG = MediaType.IMAGE_PNG;

    private final PartAttachmentRepository partAttachmentRepository;
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
                                 PartRepository partRepository,
                                 @Qualifier("datasheetRestTemplate") RestTemplate restTemplate) {
        this.partAttachmentRepository = partAttachmentRepository;
        this.partRepository = partRepository;
        this.restTemplate = restTemplate;
    }

    /** Raw bytes plus the headers needed to serve them. */
    public record AttachmentContent(byte[] data, String contentType, String filename) {}

    public List<PartAttachmentDTO> list(Long partId, AttachmentType type) {
        List<PartAttachment> rows = (type == null)
                ? partAttachmentRepository.findByPartIdOrderByDisplayOrder(partId)
                : partAttachmentRepository.findByPartIdAndTypeOrderByDisplayOrder(partId, type);
        return rows.stream().map(this::toDTO).toList();
    }

    public AttachmentContent getContent(Long partId, Long attachmentId) {
        PartAttachment a = partAttachmentRepository.findByIdAndPartId(attachmentId, partId)
                .orElseThrow(() -> new EntityNotFoundException("Attachment not found: " + attachmentId));
        return new AttachmentContent(a.getData(), a.getContentType(), a.getFilename());
    }

    @Transactional
    public PartAttachmentDTO upload(Long partId, MultipartFile file, AttachmentType type) {
        Part part = requirePart(partId);
        int order = nextDisplayOrder(partId, type);

        PartAttachment.PartAttachmentBuilder builder = PartAttachment.builder()
                .part(part)
                .type(type)
                .displayOrder(order);

        if (type == AttachmentType.PHOTO) {
            enforcePhotoLimit(partId);
            builder.data(convertToPng(file)).contentType(PNG.toString()).filename(null);
        } else {
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read file: " + e.getMessage());
            }
            if (bytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
            }
            builder.data(bytes)
                    .contentType(orDefault(file.getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE))
                    .filename(file.getOriginalFilename());
        }

        return toDTO(partAttachmentRepository.save(builder.build()));
    }

    @Transactional
    public PartAttachmentDTO uploadFromUrl(Long partId, String url, AttachmentType type) {
        Part part = requirePart(partId);
        int order = nextDisplayOrder(partId, type);

        PartAttachment.PartAttachmentBuilder builder = PartAttachment.builder()
                .part(part)
                .type(type)
                .displayOrder(order);

        if (type == AttachmentType.PHOTO) {
            enforcePhotoLimit(partId);
            builder.data(downloadAndConvertToPng(url)).contentType(PNG.toString()).filename(null);
        } else {
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
            builder.data(d.bytes()).contentType(d.contentType()).filename(filenameFromUrl(url));
        }

        return toDTO(partAttachmentRepository.save(builder.build()));
    }

    @Transactional
    public void delete(Long partId, Long attachmentId) {
        PartAttachment a = partAttachmentRepository.findByIdAndPartId(attachmentId, partId)
                .orElseThrow(() -> new EntityNotFoundException("Attachment not found: " + attachmentId));
        AttachmentType type = a.getType();
        partAttachmentRepository.delete(a);

        // Re-sequence display_order within the same type so it stays 0-based and contiguous.
        List<PartAttachment> remaining = partAttachmentRepository.findByPartIdAndTypeOrderByDisplayOrder(partId, type);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setDisplayOrder(i);
        }
        partAttachmentRepository.saveAll(remaining);
    }

    private Part requirePart(Long partId) {
        return partRepository.findById(partId)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + partId));
    }

    private int nextDisplayOrder(Long partId, AttachmentType type) {
        return partAttachmentRepository.countByPartIdAndType(partId, type);
    }

    private void enforcePhotoLimit(Long partId) {
        if (partAttachmentRepository.countByPartIdAndType(partId, AttachmentType.PHOTO) >= MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum of " + MAX_PHOTOS + " photos per part");
        }
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

    private byte[] downloadAndConvertToPng(String url) {
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

    private byte[] convertToPng(MultipartFile file) {
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

    private PartAttachmentDTO toDTO(PartAttachment a) {
        return PartAttachmentDTO.builder()
                .id(a.getId())
                .partId(a.getPart().getId())
                .type(a.getType())
                .displayOrder(a.getDisplayOrder())
                .contentType(a.getContentType())
                .filename(a.getFilename())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
