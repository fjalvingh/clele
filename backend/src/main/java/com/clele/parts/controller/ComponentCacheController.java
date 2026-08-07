package com.clele.parts.controller;

import com.clele.parts.catalog.ComponentCacheService;
import com.clele.parts.dto.ComponentCacheDetailDTO;
import com.clele.parts.dto.ComponentCacheMatchDTO;
import com.clele.parts.dto.ComponentCacheStatusDTO;
import com.clele.parts.model.Permissions;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The local component cache — the first place a part lookup should go.
 *
 * <p>It is free, offline and instant, which is the whole argument for consulting it ahead of the web
 * and AI paths: those cost roughly 5–13 cents a call and take seconds, and for a mass-market part
 * they mostly rediscover what this snapshot already holds.
 *
 * <p>Gated on {@code PARTS_EDIT} even though nothing here costs money or writes anything, because it
 * is an intake path: every caller ends in creating or enriching a part, which needs that permission
 * anyway. {@link #status} is the exception — the SPA asks it to decide whether to offer the stage at
 * all, and a read-only member's Quick Add screen should not break on a 403.
 */
@RestController
@RequestMapping("/api/component-cache")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class ComponentCacheController {

    private final ComponentCacheService componentCacheService;

    /** Whether the snapshot is installed, and how old it is. Authenticated, no permission needed. */
    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ComponentCacheStatusDTO status() {
        return componentCacheService.status();
    }

    /**
     * Matching parts, best first. Returns an empty list — never an error — when the cache is not
     * installed or the term is too short to match on, so a caller can always try it first and move
     * on.
     */
    @GetMapping("/search")
    public List<ComponentCacheMatchDTO> search(@RequestParam String q) {
        return componentCacheService.search(q);
    }

    /**
     * Everything the cache holds about one part, mapped onto this app's fields and spec keys.
     *
     * <p>Writes nothing: the caller applies it through Quick Add's create or
     * {@code POST /api/parts/{id}/ai-apply}, so a value only lands where the user put it.
     */
    @GetMapping("/{lcsc}")
    public ComponentCacheDetailDTO load(@PathVariable String lcsc) {
        return componentCacheService.load(lcsc);
    }
}
