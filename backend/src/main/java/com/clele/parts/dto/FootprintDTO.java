package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FootprintDTO {
    private Long id;
    private String name;
    private String description;
    private List<String> aliases;
    /** How many of the organisation's parts carry this footprint. */
    private long partCount;
}
