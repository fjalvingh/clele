package com.clele.parts.dto;

import com.clele.parts.model.ProjectStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDTO {
    private Long id;
    private String name;
    private String description;
    private ProjectStatus status;
    private int instanceCount;
    private Long ownerId;
    private String ownerName;
    private int partCount;
    private BigDecimal totalStockValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** True when some part list line holds less than the build needs — stock ran short. */
    private boolean anyShortfall;

    /** The project's part list. Populated only by the detail endpoint (null in list responses). */
    private List<ProjectPartDTO> parts;
}
