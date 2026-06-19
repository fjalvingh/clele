package com.clele.parts.controller;

import com.clele.parts.dto.OctopartApplyRequest;
import com.clele.parts.dto.OctopartResultDTO;
import com.clele.parts.dto.OctopartUsageDTO;
import com.clele.parts.dto.PartDTO;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.CurrentUserService;
import com.clele.parts.service.OctopartService;
import com.clele.parts.service.PartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OctoPart (Nexar) enrichment endpoints. Search consumes the current user's monthly quota; apply
 * is free. Credentials and quota are per-user (see {@link com.clele.parts.service.OctopartService}).
 */
@RestController
@RequestMapping("/api/parts/octopart")
@RequiredArgsConstructor
@Tag(name = "OctoPart", description = "Enrich parts from OctoPart (Nexar)")
public class OctopartController {

    private final OctopartService octopartService;
    private final PartService partService;
    private final CurrentUserService currentUserService;

    @GetMapping("/usage")
    @Operation(summary = "Current user's OctoPart monthly request usage")
    public OctopartUsageDTO usage() {
        return octopartService.usage(currentUserService.current());
    }

    @GetMapping("/search")
    @Operation(summary = "Search OctoPart by MPN (consumes one monthly request)")
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    public List<OctopartResultDTO> search(@RequestParam("q") String query) {
        return octopartService.search(currentUserService.current(), query);
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Apply a chosen OctoPart result to a part (no quota cost)")
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    public PartDTO apply(@PathVariable Long id, @Valid @RequestBody OctopartApplyRequest request) {
        return partService.applyOctopart(id, request);
    }
}
