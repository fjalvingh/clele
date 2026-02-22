package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageFromUrlRequest {
    @NotBlank(message = "URL is required")
    private String url;
}
