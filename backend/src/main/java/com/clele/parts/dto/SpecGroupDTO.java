package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecGroupDTO {
    private Long id;
    private String name;
    private String description;
    private int displayOrder;
    /** Number of spec definitions filed under this group. */
    private long specCount;
}
