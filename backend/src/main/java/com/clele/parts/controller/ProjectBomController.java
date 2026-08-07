package com.clele.parts.controller;

import com.clele.parts.dto.*;
import com.clele.parts.model.Permissions;
import com.clele.parts.model.ProjectBom;
import com.clele.parts.service.bom.ProjectBomImportService;
import com.clele.parts.service.bom.ProjectBomService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The imported BOM of one project: uploading it, matching its lines to catalogue parts, and pushing
 * what has been matched into the project's own BOM.
 *
 * <p>Sits under {@code /api/projects/{projectId}/bom} beside {@link ProjectController}'s
 * {@code /bom} sub-resource, and carries the same class-level permission. Every method resolves the
 * project through {@code ProjectService.requireOwnProject}, so a project belonging to another user
 * or organisation is a 404 here too.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/bom")
@RequiredArgsConstructor
@Tag(name = "Project BOM", description = "Imported bill of materials and part matching")
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class ProjectBomController {

    private final ProjectBomService bomService;
    private final ProjectBomImportService importService;
    private final ObjectMapper objectMapper;

    @GetMapping
    @Operation(summary = "Get the project's imported BOM with every line and its match")
    public ResponseEntity<ProjectBomDTO> getBom(@PathVariable Long projectId) {
        ProjectBomDTO bom = bomService.find(projectId);
        // 204 rather than 404: the project exists and simply has no BOM yet, which is the normal
        // starting state and not something the SPA should treat as an error.
        return bom == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(bom);
    }

    /**
     * Uploads a BOM export. <b>A dry run unless {@code commit} is true</b> — the response reports
     * what a commit would add, update and remove, together with the column mapping that was
     * detected, so the user can correct the mapping and see the damage before agreeing to it.
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import (or preview importing) a BOM file, merging it into the existing BOM")
    public BomImportPreviewDTO importBom(@PathVariable Long projectId,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "mapping", required = false) String mappingJson,
                                         @RequestParam(value = "commit", defaultValue = "false") boolean commit) {
        Map<String, String> mapping = parseMapping(mappingJson);
        return commit
                ? importService.commit(projectId, file, mapping)
                : importService.preview(projectId, file, mapping);
    }

    @GetMapping("/file")
    @Operation(summary = "Download the BOM file as it was uploaded")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long projectId) {
        ProjectBom bom = bomService.requireBom(projectId);
        if (bom.getData() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No file is stored for this BOM");
        }
        String filename = bom.getFilename() == null ? "bom.csv" : bom.getFilename();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        bom.getContentType() == null ? "text/csv" : bom.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(bom.getData());
    }

    @GetMapping("/lines/{lineId}/candidates")
    @Operation(summary = "Ranked part suggestions for one BOM line")
    public List<BomCandidateDTO> candidates(@PathVariable Long projectId, @PathVariable Long lineId) {
        return bomService.candidates(projectId, lineId);
    }

    @PutMapping("/lines/{lineId}")
    @Operation(summary = "Match a BOM line to a part, or mark it provided / excluded / unmatched")
    public ProjectBomLineDTO setMatch(@PathVariable Long projectId,
                                      @PathVariable Long lineId,
                                      @RequestBody BomLineMatchRequest request) {
        return bomService.setMatch(projectId, lineId, request);
    }

    @PostMapping("/apply")
    @Operation(summary = "Push the matched lines into the project's BOM (project_part)")
    public BomApplyResultDTO apply(@PathVariable Long projectId) {
        return bomService.apply(projectId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete the imported BOM and all of its lines")
    public void deleteBom(@PathVariable Long projectId) {
        bomService.delete(projectId);
    }

    /**
     * The mapping rides along as a JSON string rather than a JSON body, because the request is
     * multipart — mixing a file part with an {@code application/json} part works but makes the
     * client's upload noticeably more awkward for one small map.
     */
    private Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read the column mapping: " + e.getMessage());
        }
    }
}
