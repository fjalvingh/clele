package com.clele.parts.dto;

import lombok.*;

import java.util.Map;

/** A single OctoPart (Nexar) search result, mapped onto Clele's part fields. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OctopartResultDTO {
    /** Nexar part id — stored as part.octopartId, the link back to OctoPart. */
    private String octopartId;
    private String mpn;
    private String manufacturer;
    private String description;
    private String datasheetUrl;
    private String footprint;
    /** Spec key → value, keyed to match part.specs JSON. */
    private Map<String, Object> specs;
}
