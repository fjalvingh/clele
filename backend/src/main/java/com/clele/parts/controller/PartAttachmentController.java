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
        MediaType mediaType = parseMediaType(content.contentType());

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS));

        // Photos render inline; datasheets/attachments download with their original filename.
        if (content.filename() != null && !content.filename().isBlank()) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(content.filename()).build().toString());
        }
        return builder.body(content.data());
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

    private static MediaType parseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
