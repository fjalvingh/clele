package com.clele.parts.controller;

import com.clele.parts.dto.TagDTO;
import com.clele.parts.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
@Tag(name = "Tags", description = "Part tag autocomplete")
public class TagController {

    private final TagService tagService;

    @GetMapping
    @Operation(summary = "Search existing tags by name (case-insensitive substring match)")
    public List<TagDTO> search(@RequestParam(required = false) String q) {
        return tagService.search(q);
    }
}
