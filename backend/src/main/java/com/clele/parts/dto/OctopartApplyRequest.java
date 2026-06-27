package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.Map;

/**
 * Applies a chosen OctoPart result to a part. {@code octopartId} sets the link and {@code specs}
 * are applied wholesale. Each nullable column field carries a value only when the user accepted
 * that change (ticked checkbox); a null field leaves the existing part column unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OctopartApplyRequest {

    @NotBlank(message = "octopartId is required")
    private String octopartId;

    private String description;
    private String manufacturer;
    private String mpn;
    private String footprint;
    private String datasheetUrl;

    /** Full set of OctoPart specs to overlay onto part.specs. */
    private Map<String, Object> specs;
}
