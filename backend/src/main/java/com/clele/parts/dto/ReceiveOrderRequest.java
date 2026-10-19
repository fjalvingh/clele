package com.clele.parts.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Parts that came in: per order line how many, and where they were put. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveOrderRequest {

    @NotEmpty(message = "Nothing to receive")
    private List<@Valid Receipt> receipts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Receipt {
        @NotNull(message = "Line is required")
        private Long lineId;
        @Min(value = 1, message = "Quantity must be at least 1")
        private int quantity;
        @NotNull(message = "Location is required")
        private Long locationId;
    }
}
