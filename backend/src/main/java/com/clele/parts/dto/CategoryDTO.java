package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private String parentName;
    private String breadcrumb;
}
