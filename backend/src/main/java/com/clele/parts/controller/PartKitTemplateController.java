package com.clele.parts.controller;

import com.clele.parts.dto.PartKitGenerateRequest;
import com.clele.parts.dto.PartKitGenerateResultDTO;
import com.clele.parts.dto.PartKitGenerationDTO;
import com.clele.parts.dto.PartKitUndoResultDTO;
import com.clele.parts.dto.PartKitTemplateDTO;
import com.clele.parts.dto.PartAttachmentDTO;
import com.clele.parts.dto.PartKitTemplateRequest;
import com.clele.parts.service.PartAttachmentService.AttachmentContent;
import com.clele.parts.service.PartKitGenerationService;
import com.clele.parts.service.PartKitTemplateImageService;
import com.clele.parts.service.PartKitTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/part-kit-templates")
@RequiredArgsConstructor
@Tag(name = "Part kit templates", description = "Templates for packs of parts that differ in one value")
@PreAuthorize("hasAuthority('PARTS_EDIT')")
public class PartKitTemplateController {

    private final PartKitTemplateService service;
    private final PartKitTemplateImageService imageService;
    private final PartKitGenerationService generationService;

    @GetMapping
    @Operation(summary = "List the current organisation's kit templates")
    public List<PartKitTemplateDTO> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one kit template with its values")
    public PartKitTemplateDTO get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a kit template")
    public ResponseEntity<PartKitTemplateDTO> create(@Valid @RequestBody PartKitTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a kit template, including its whole value list")
    public PartKitTemplateDTO update(@PathVariable Long id,
                                     @Valid @RequestBody PartKitTemplateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a kit template (the parts it generated are untouched)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Images: the photos every generated part is given ──────────────────────
    //
    // Same store and the same URL shape as a part's attachments, one level up: the template holds
    // links to part_attachment rows, and generating hands those same rows to each new part.

    @GetMapping("/{id}/images")
    @Operation(summary = "The images this template gives to every part it generates")
    public List<PartAttachmentDTO> images(@PathVariable Long id) {
        return imageService.list(id);
    }

    @GetMapping("/{id}/images/{attachmentId}")
    @Operation(summary = "Serve one template image")
    public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable Long attachmentId) {
        AttachmentContent content = imageService.getContent(id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(content.data());
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an image onto the template")
    public ResponseEntity<PartAttachmentDTO> uploadImage(@PathVariable Long id,
                                                         @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(imageService.upload(id, file));
    }

    @DeleteMapping("/{id}/images/{attachmentId}")
    @Operation(summary = "Take an image off the template (parts already generated keep theirs)")
    public ResponseEntity<Void> deleteImage(@PathVariable Long id, @PathVariable Long attachmentId) {
        imageService.delete(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/generate")
    @Operation(summary = "Create or find a part per value and add stock to each")
    public PartKitGenerateResultDTO generate(@PathVariable Long id,
                                             @Valid @RequestBody PartKitGenerateRequest request) {
        return service.generate(id, request);
    }

    // ── Generation history and undo ───────────────────────────────────────────
    //
    // Generating makes dozens of rows from one click, so every run is recorded and the most recent
    // one can be taken back whole — see PartKitGenerationService for what "undoable" means.

    @GetMapping("/{id}/generations")
    @Operation(summary = "Past runs of this kit, newest first, each saying whether it can be undone")
    public List<PartKitGenerationDTO> generations(@PathVariable Long id) {
        return generationService.findByTemplate(id);
    }

    @PostMapping("/{id}/generations/{generationId}/undo")
    @Operation(summary = "Take back a generation run: remove its stock and delete the parts it created",
            description = "Only the kit's most recent run, and only while nothing it made has been "
                    + "touched — otherwise 409 with the reason.")
    public PartKitUndoResultDTO undo(@PathVariable Long id, @PathVariable Long generationId) {
        return generationService.undo(id, generationId);
    }
}
