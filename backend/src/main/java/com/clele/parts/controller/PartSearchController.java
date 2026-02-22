package com.clele.parts.controller;

import com.clele.parts.dto.ImageSuggestionDTO;
import com.clele.parts.dto.PartSearchResultDTO;
import com.clele.parts.dto.QuickAddRequest;
import com.clele.parts.dto.QuickAddResponseDTO;
import com.clele.parts.service.AiPartSearchService;
import com.clele.parts.service.QuickAddService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/api/parts/quick-add")
    @ResponseStatus(HttpStatus.CREATED)
    public QuickAddResponseDTO quickAdd(@Valid @RequestBody QuickAddRequest request) {
        return quickAddService.quickAdd(request);
    }
}
