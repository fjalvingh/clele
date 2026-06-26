package com.clele.parts.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationTreeDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private Long ownerId;
    private String ownerName;
    private List<LocationTreeDTO> children;
}
