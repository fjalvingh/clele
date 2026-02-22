package com.clele.parts.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryTreeDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private List<CategoryTreeDTO> children;
}
