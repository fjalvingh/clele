package com.clele.parts.controller;

import com.clele.parts.dto.AiApplyRequest;
import com.clele.parts.dto.DatasheetExtractionDTO;
import com.clele.parts.dto.DatasheetSearchResponseDTO;
import com.clele.parts.dto.ImageSuggestionDTO;
import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.PartSearchResultDTO;
import com.clele.parts.dto.QuickAddRequest;
import com.clele.parts.dto.QuickAddResponseDTO;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.AiPartSearchService;
import com.clele.parts.service.DatasheetSpecExtractionService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.QuickAddService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The AI lookup endpoints.
 *
 * <p>All of them require {@code PARTS_EDIT}. They are the only endpoints in the app that spend real
 * money per call — a part search runs web searches and costs roughly 8-13 cents each — so leaving
 * them open to any authenticated member would let a read-only account drain the organisation's
 * Anthropic budget. Nothing is lost by gating them: every caller ends in a mutation that already
 * requires the same permission, so a user who could search but not save could do nothing with the
 * result anyway.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class PartSearchController {

    private final AiPartSearchService aiPartSearchService;
    private final DatasheetSpecExtractionService datasheetSpecExtractionService;
    private final PartService partService;
    private final QuickAddService quickAddService;

    @GetMapping("/api/parts-search")
    public List<PartSearchResultDTO> search(@RequestParam String q) {
        return aiPartSearchService.search(q);
    }

    @GetMapping("/api/parts-search/images")
    public List<ImageSuggestionDTO> searchImages(@RequestParam String q) {
        return aiPartSearchService.searchImages(q);
    }

    @GetMapping("/api/parts-search/datasheets")
    public DatasheetSearchResponseDTO searchDatasheets(
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "false") boolean forceAi) {
        return aiPartSearchService.searchDatasheets(q, forceAi);
    }

    /**
     * Reads a datasheet already stored on the part and proposes specs plus a description from it.
     *
     * <p>Writes nothing — the caller confirms the proposal and applies it through
     * {@link #applyAiLookup}, the same path the web lookup uses. {@code attachmentId} is optional:
     * omitted, the part's first stored datasheet is read, which is the usual case.
     *
     * <p>A POST rather than a GET despite reading nothing but the database: it costs money and is
     * not idempotent in the way a cache or a prefetch would assume.
     */
    @PostMapping("/api/parts/{id}/datasheet-extract")
    public DatasheetExtractionDTO extractFromDatasheet(
            @PathVariable Long id,
            @RequestParam(required = false) Long attachmentId) {
        return datasheetSpecExtractionService.extract(id, attachmentId);
    }

    /**
     * Applies a chosen lookup result to an existing part. Free — the search already happened; this
     * only writes what the user ticked.
     */
    @PostMapping("/api/parts/{id}/ai-apply")
    public PartDTO applyAiLookup(@PathVariable Long id, @RequestBody AiApplyRequest request) {
        return partService.applyAiLookup(id, request);
    }

    @PostMapping("/api/parts/quick-add")
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddResponseDTO quickAdd(@Valid @RequestBody QuickAddRequest request) {
        QuickAddResponseDTO response = quickAddService.quickAdd(request);
        // Fetch the datasheet here rather than inside quickAdd: that method is transactional, and a
        // vendor download is slow and allowed to fail. Running it after the commit means a dead link
        // cannot take the newly created part down with it. See attachDatasheetBestEffort.
        quickAddService.attachDatasheetBestEffort(response.getPart().getId(), request.getDatasheetUrl());
        return response;
    }
}
