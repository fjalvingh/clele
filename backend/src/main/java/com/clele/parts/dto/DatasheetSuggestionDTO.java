package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasheetSuggestionDTO {
    private String url;    // candidate datasheet URL (usually a PDF)
    private String title;  // result title/label, for display
    private String source; // hostname the result came from, for display
}
