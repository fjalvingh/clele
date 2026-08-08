package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartKitTemplateRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String notes;

    @NotBlank(message = "Part number template is required")
    private String partNumberTemplate;

    private boolean personalNumber;
    private String manufacturerTemplate;
    private String descriptionTemplate;
    private String detailsTemplate;
    private String footprintTemplate;
    private String datasheetUrlTemplate;

    private Long categoryId;

    /** Keyed by {@code spec_definition.json_name}; every value is a template string. */
    private Map<String, Object> specs;

    private List<String> tags;

    /**
     * The whole value list, in order. Sent as a list rather than as add/remove operations: the
     * screen edits it as a list and duplicates/blanks are normalised server-side, so one payload
     * cannot leave the stored order disagreeing with what the user saw.
     */
    private List<String> values;
}
