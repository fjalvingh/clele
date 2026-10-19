package com.clele.parts.dto;

import com.clele.parts.model.PurchaseOrderStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An order. {@code lines} is filled on the detail view only. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderDTO {
    private Long id;
    private Long supplierId;
    private String supplierName;
    private PurchaseOrderStatus status;
    private String supplierReference;
    private String notes;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime orderedAt;
    private LocalDateTime receivedAt;
    private long lineCount;
    private BigDecimal total;
    private List<PurchaseOrderLineDTO> lines;
}
