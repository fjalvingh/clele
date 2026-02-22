package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageSuggestionDTO {
    private String url;          // original image URL — used for saving to the part
    private String thumbnailUrl; // smaller preview URL — used for display only (may be null)
    private String description;
}
