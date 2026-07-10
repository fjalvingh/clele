package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartRequest {

    @NotBlank(message = "Part number is required")
    private String partNumber;

    private String description;
    private String details;
    private String manufacturer;
    private String datasheetUrl;
    private Map<String, Object> specs;
    private Long categoryId;
    private List<String> tags;
}
