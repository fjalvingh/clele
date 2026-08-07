package com.clele.parts.service;

import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.SpecDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders the current organisation's spec fields as the bullet list a prompt injects, so that a
 * model answers keyed by {@code spec_definition.json_name} and its reply lands in {@code part.specs}
 * without a translation step.
 *
 * <p>Shared by every path that asks a model for specifications — the web-search part lookup
 * ({@link AiPartSearchService}) and the datasheet reader
 * ({@link DatasheetSpecExtractionService}). They must describe the catalogue identically: a spec
 * offered as {@code "Capacitance: 100 nF"} in one prompt and as a bare number in the other produces
 * two different stored values for the same field, and nothing downstream can tell them apart.
 */
@Component
@RequiredArgsConstructor
public class SpecFieldCatalog {

    private final SpecDefinitionRepository specDefinitionRepository;
    private final CurrentOrganisationService currentOrganisationService;

    /** The rendered list plus the figure that explains its size, for cost logging. */
    public record Fields(String text, int count) {}

    public Fields render() {
        List<SpecDefinition> defs = specDefinitionRepository
                .findByOrganisationIdOrderByDisplayOrderAscNameAsc(currentOrganisationService.currentId());
        StringBuilder sb = new StringBuilder();
        for (SpecDefinition def : defs) {
            sb.append("\n  - \"").append(def.getJsonName()).append("\" (").append(def.getName()).append(")");
            if ("SELECT".equals(def.getDataType()) && def.getOptions() != null) {
                sb.append("  (options: ").append(def.getOptions()).append(")");
            } else if ("NUMBER".equals(def.getDataType()) && def.getUnit() != null) {
                sb.append("  (unit: ").append(def.getUnit()).append(")");
            } else if ("BOOLEAN".equals(def.getDataType())) {
                sb.append("  (true/false)");
            }
        }
        return new Fields(sb.toString(), defs.size());
    }
}
