package com.clele.parts.controller;

import com.clele.parts.dto.AttachmentFromUrlRequest;
import com.clele.parts.dto.PartAttachmentDTO;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.PartAttachmentService;
import com.clele.parts.service.PartAttachmentService.AttachmentContent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/parts/{partId}/attachments")
@RequiredArgsConstructor
public class PartAttachmentController {

    private final PartAttachmentService partAttachmentService;

    @GetMapping
    public List<PartAttachmentDTO> list(@PathVariable Long partId,
                                        @RequestParam(value = "type", required = false) AttachmentType type) {
        return partAttachmentService.list(partId, type);
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> get(@PathVariable Long partId, @PathVariable Long attachmentId) {
        AttachmentContent content = partAttachmentService.getContent(partId, attachmentId);
        MediaType mediaType = effectiveMediaType(content.contentType(), content.filename());

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(mediaType);

        // Photos render inline (no filename). For datasheets/attachments, let browser-viewable
        // types (PDFs, images) open inline in a new tab and force a download for everything else.
        if (content.filename() != null && !content.filename().isBlank()) {
            ContentDisposition disposition = isInlineViewable(mediaType)
                    ? ContentDisposition.inline().filename(content.filename()).build()
                    : ContentDisposition.attachment().filename(content.filename()).build();
            builder.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            // Datasheets/attachments must revalidate so a changed server response (e.g. a fix to the
            // disposition/content-type) takes effect instead of being pinned by a long browser cache.
            builder.cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic().mustRevalidate());
        } else {
            // Photos never change in place — keep them long and immutable.
            builder.cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS));
        }
        return builder.body(content.data());
    }

    /** Content types browsers can render directly, so they should open in-tab rather than download. */
    private static boolean isInlineViewable(MediaType mediaType) {
        return MediaType.APPLICATION_PDF.includes(mediaType)
                || new MediaType("image").includes(mediaType);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    public PartAttachmentDTO upload(@PathVariable Long partId,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "type", defaultValue = "PHOTO") AttachmentType type) {
        return partAttachmentService.upload(partId, file, type);
    }

    @PostMapping("/from-url")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    public PartAttachmentDTO uploadFromUrl(@PathVariable Long partId,
                                           @jakarta.validation.Valid @RequestBody AttachmentFromUrlRequest request) {
        return partAttachmentService.uploadFromUrl(partId, request.getUrl(), request.getType());
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    public void delete(@PathVariable Long partId, @PathVariable Long attachmentId) {
        partAttachmentService.delete(partId, attachmentId);
    }

    /**
     * Resolve the media type to serve. Many sources hand back PDFs as a generic
     * {@code application/octet-stream}, which makes browsers download them; recover the real type
     * from the filename extension so they open inline instead.
     */
    private static MediaType effectiveMediaType(String contentType, String filename) {
        MediaType parsed = parseMediaType(contentType);
        if (parsed.equalsTypeAndSubtype(MediaType.APPLICATION_OCTET_STREAM) && filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
            if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
            if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        }
        return parsed;
    }

    private static MediaType parseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
