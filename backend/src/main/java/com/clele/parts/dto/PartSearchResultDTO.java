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
    private List<String> specs;
}
