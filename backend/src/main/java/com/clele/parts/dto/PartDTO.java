package com.clele.parts.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartDTO {
    private Long id;
    private String partNumber;
    private String description;
    private String details;
    private String manufacturer;
    private String footprint;
    private String mpn;
    private String octopartId;
    private String datasheetUrl;
    private Map<String, Object> specs;
    private Long categoryId;
    private String categoryName;
    private String categoryBreadcrumb;
    private Long createdById;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long totalQuantity;
    private List<String> tags;
}
