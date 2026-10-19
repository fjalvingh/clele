package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A supplier file (the matched order sent back, or a delivery slip) read against one order. Nothing
 * is stored: the SPA shows this, lets the user fix what did not match, and then sends the result
 * as an ordinary replace-lines or receive request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderFilePreviewDTO {
    private List<String> headers;
    /** Role name → header, as detected or as overridden by the request. */
    private Map<String, String> mapping;
    private List<Row> rows;
    /** Order lines no row of the file accounts for. */
    private List<PurchaseOrderLineDTO> missingLines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Row {
        /** 1-based row number in the file, for the user to find it. */
        private int rowNumber;
        private String supplierPartNumber;
        private String mpn;
        private String manufacturer;
        private String customerPartNumber;
        private String description;
        private Integer quantity;
        private BigDecimal unitPrice;
        /** The order line the row was matched to, if any. */
        private Long lineId;
        /** The part the row was matched to: through its line, or found in the catalogue. */
        private Long partId;
        private String partNumber;
        private String partDescription;
        /** CUSTOMER_PART_NUMBER, SUPPLIER_PART_NUMBER, MPN or CATALOGUE; null when unmatched. */
        private String matchedBy;
        /** Something the user should look at before accepting the row. */
        private String warning;
    }
}
