package com.clele.parts.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecDefinitionDTO {
    private Long id;
    private String name;
    private String dataType;
    private String unit;
    private List<String> options;
    private int displayOrder;
}
