package com.clele.parts.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecDefinitionDTO {
    private Long id;
    private String jsonName;
    private String name;
    private String dataType;
    private String unit;
    private boolean metricPrefix;
    private List<String> options;
    private int displayOrder;
    private Long groupId;
    private String groupName;
    /** Alternate JSON names this spec is also known by (from merges or set by hand). */
    private List<String> aliases;
}
