package com.clele.parts.controller;

import com.clele.parts.dto.DatasheetSuggestionDTO;
import com.clele.parts.dto.ImageSuggestionDTO;
import com.clele.parts.dto.PartSearchResultDTO;
import com.clele.parts.dto.QuickAddRequest;
import com.clele.parts.dto.QuickAddResponseDTO;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.AiPartSearchService;
import com.clele.parts.service.QuickAddService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PartSearchController {

    private final AiPartSearchService aiPartSearchService;
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
    public List<DatasheetSuggestionDTO> searchDatasheets(
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "false") boolean forceAi) {
        return aiPartSearchService.searchDatasheets(q, forceAi);
    }

    @PostMapping("/api/parts/quick-add")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    public QuickAddResponseDTO quickAdd(@Valid @RequestBody QuickAddRequest request) {
        return quickAddService.quickAdd(request);
    }
}
