package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private String parentName;
    /** Full path from the root, e.g. "Building A > Room B > Cupboard C". */
    private String breadcrumb;
}
