package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpecDefinitionRequest {

    @NotBlank(message = "JSON name is required")
    private String jsonName;

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Data type is required")
    private String dataType;

    private String unit;

    /** NUMBER with a single base SI unit: render/edit the value with metric prefixes. */
    private boolean metricPrefix;

    /** UnitFamily code, or null/blank for "never parse this field's values". */
    private String unitFamily;

    private List<String> options;

    private int displayOrder;

    /** The group this spec belongs to. Defaults to the organisation's first group when null. */
    private Long groupId;

    /** Alternate JSON names for this spec. Null leaves the existing aliases untouched. */
    private List<String> aliases;
}
