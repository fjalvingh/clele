package com.clele.parts.controller;

import com.clele.parts.dto.McpApiKeyCreatedDTO;
import com.clele.parts.dto.McpApiKeyDTO;
import com.clele.parts.dto.McpApiKeyRequest;
import com.clele.parts.service.McpApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The current user's own MCP keys. Self-service, like the rest of {@code /api/profile}: a key
 * carries no more access than its owner already has, so issuing one needs no permission beyond
 * being able to log in.
 */
@RestController
@RequestMapping("/api/profile/mcp-keys")
@RequiredArgsConstructor
@Tag(name = "MCP", description = "Keys for the read-only MCP endpoint")
public class McpApiKeyController {

    private final McpApiKeyService mcpApiKeyService;

    @GetMapping
    @Operation(summary = "The current user's MCP keys (never the tokens)")
    public List<McpApiKeyDTO> list() {
        return mcpApiKeyService.findMine();
    }

    @PostMapping
    @Operation(summary = "Issue an MCP key; the token is returned once and never again")
    public ResponseEntity<McpApiKeyCreatedDTO> create(@Valid @RequestBody McpApiKeyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mcpApiKeyService.create(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke one of the current user's MCP keys")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        mcpApiKeyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
