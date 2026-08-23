package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PartSearchResultDTO {
    private String mpn;
    private String manufacturer;
    private String shortDescription;
    private String datasheetUrl;
    private String category;
    /**
     * A few sentences on what the part is and does. Only the datasheet reader fills this in — a web
     * search result has no equivalent, and inventing one from search snippets is how a description
     * ends up describing the wrong member of a family.
     */
    private String details;
    private List<String> specs;
}
