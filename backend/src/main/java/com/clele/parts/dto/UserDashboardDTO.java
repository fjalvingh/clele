package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;

/** Per-user breakdown of stock held in the locations a user owns. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDashboardDTO {
    private Long userId;
    private String userName;
    private Long locations;
    private Long parts;
    private Long totalQuantity;
    private BigDecimal totalStockValue;
    private Long lowStockCount;
}
