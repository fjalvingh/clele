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
}
