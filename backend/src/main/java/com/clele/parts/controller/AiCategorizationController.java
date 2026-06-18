package com.clele.parts.controller;

import com.clele.parts.dto.CategorizationStatusDTO;
import com.clele.parts.service.PartCategorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/parts/auto-categorize")
@RequiredArgsConstructor
@Tag(name = "AI Categorization", description = "Local-AI (Ollama) auto-categorization of parts")
public class AiCategorizationController {

    private final PartCategorizationService categorizationService;

    @PostMapping
    @Operation(summary = "Start a background job that auto-assigns parts to categories via Ollama")
    public CategorizationStatusDTO start(
            @RequestParam(defaultValue = "false") boolean onlyUncategorized) {
        return categorizationService.start(onlyUncategorized);
    }

    @GetMapping("/status")
    @Operation(summary = "Get the current auto-categorization job progress")
    public CategorizationStatusDTO status() {
        return categorizationService.status();
    }
}
