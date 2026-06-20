package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovementDTO {
    private Long id;
    private Long partId;
    private Long locationId;
    private String locationName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private String currency;
    private String comments;
    private LocalDateTime movedAt;
    private String createdBy;
    private com.clele.parts.model.MovementType type;
}
