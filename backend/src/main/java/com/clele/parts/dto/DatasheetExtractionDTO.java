package com.clele.parts.dto;

import lombok.Builder;

import java.util.List;

/**
 * What reading a stored datasheet produced — a <em>proposal</em>, not a change. The caller confirms
 * it field by field and applies it through {@code POST /parts/{id}/ai-apply}.
 *
 * <p>The routing figures travel with the result because they explain a thin one. A document routed
 * {@code IMAGE_TABLES} has its parametric tables pasted in as scans, so the text layer carries the
 * description and little else; reporting three specs from it as though the document had been read
 * whole would be the same mistake as reporting a blocked web search as "no results found".
 *
 * @param attachmentId the datasheet that was read
 * @param filename     its stored filename, for naming it back to the user
 * @param route        {@code TEXT} or {@code IMAGE_TABLES} — see {@code DatasheetAnalyzer.Route}
 * @param pages        pages in the document (not in the excerpt)
 * @param headings     the parametric section headings found, which is what the excerpt was cut around
 * @param excerptChars how much text was actually sent to the model
 * @param details      a functional description drawn from the datasheet, for {@code part.details}
 * @param specs        the extracted values, keys already canonicalized through the alias table
 */
@Builder
public record DatasheetExtractionDTO(
        Long attachmentId,
        String filename,
        String route,
        int pages,
        List<String> headings,
        int excerptChars,
        String details,
        List<ExtractedSpecDTO> specs) {}
