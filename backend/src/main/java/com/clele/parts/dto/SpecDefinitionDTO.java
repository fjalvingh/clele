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

    /**
     * The {@link com.clele.parts.model.UnitFamily} code, or null. What the field measures, which is
     * what lets the client render a stored base-unit number back into the form people write
     * (0.00000015 -> "150 ns") without the definition having to declare a unit of its own.
     */
    private String unitFamily;
    private List<String> options;
    private int displayOrder;
    private Long groupId;
    private String groupName;
    /** Alternate JSON names this spec is also known by (from merges or set by hand). */
    private List<String> aliases;
}
