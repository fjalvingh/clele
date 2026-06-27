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
    private int bomPartCount;
    private BigDecimal totalStockValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Populated only by the detail endpoint (null in list responses). */
    private List<ProjectBomEntryDTO> bom;
    /** Populated only by the detail endpoint (null in list responses). */
    private List<ProjectStockEntryDTO> stock;
}
