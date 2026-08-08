package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartKitTemplateDTO {

    private Long id;
    private String name;
    private String notes;

    private String partNumberTemplate;
    private boolean personalNumber;
    private String manufacturerTemplate;
    private String descriptionTemplate;
    private String detailsTemplate;
    private String footprintTemplate;
    private String datasheetUrlTemplate;

    private Long categoryId;
    private String categoryName;
    private String categoryBreadcrumb;

    private Map<String, Object> specs;
    private List<String> tags;

    /** The values the kit varies over, in display order. */
    private List<String> values;

    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
