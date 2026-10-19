package com.clele.parts.service.order;

import com.clele.parts.service.order.OrderFileColumnMapper.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderFileColumnMapperTest {

    private final OrderFileColumnMapper mapper = new OrderFileColumnMapper();

    @Test
    @DisplayName("a Mouser-style export maps, with the header found below a title block")
    void mapsMouserExport() {
        List<List<String>> records = List.of(
                List.of("Mouser Electronics"),
                List.of("Project: Clock board", "Date: 2026-10-19"),
                List.of("Mouser No:", "Mfr. No:", "Manufacturer:", "Customer No:", "Description:",
                        "Order Qty.", "Price (EUR)", "Ext.: (EUR)"),
                List.of("71-CRCW0805-10K", "CRCW080510K0FKEA", "Vishay", "R-10K-0805", "Thick film", "100",
                        "0.0034", "0.34"));

        OrderFileColumnMapper.Detected detected = mapper.detect(records, Kind.ORDER, "Mouser");

        assertEquals(2, detected.headerRow());
        assertEquals("Mouser No:", detected.mapping().get(OrderFileColumn.SUPPLIER_PART_NUMBER));
        assertEquals("Mfr. No:", detected.mapping().get(OrderFileColumn.MPN));
        assertEquals("Manufacturer:", detected.mapping().get(OrderFileColumn.MANUFACTURER));
        assertEquals("Customer No:", detected.mapping().get(OrderFileColumn.CUSTOMER_PART_NUMBER));
        assertEquals("Order Qty.", detected.mapping().get(OrderFileColumn.QUANTITY));
        assertEquals("Price (EUR)", detected.mapping().get(OrderFileColumn.UNIT_PRICE),
                "the extended price must not be taken for the unit price");
    }

    @Test
    @DisplayName("our own order file comes back mapped, whatever the supplier is called")
    void mapsOwnExport() {
        List<List<String>> records = List.of(List.of("Farnell Part Number", "Manufacturer Part Number",
                "Manufacturer", "Customer Part Number", "Quantity", "Description"));

        OrderFileColumnMapper.Detected detected = mapper.detect(records, Kind.ORDER, "Farnell");

        assertEquals("Farnell Part Number", detected.mapping().get(OrderFileColumn.SUPPLIER_PART_NUMBER));
        assertEquals("Manufacturer Part Number", detected.mapping().get(OrderFileColumn.MPN));
        assertEquals("Customer Part Number", detected.mapping().get(OrderFileColumn.CUSTOMER_PART_NUMBER));
        assertEquals("Quantity", detected.mapping().get(OrderFileColumn.QUANTITY));
    }

    @Test
    @DisplayName("on a delivery slip the shipped quantity wins over the ordered one")
    void deliveryTakesShippedQuantity() {
        List<List<String>> records = List.of(List.of("Mouser No", "Order Qty", "Qty Shipped", "Back Order"));

        OrderFileColumnMapper.Detected detected = mapper.detect(records, Kind.DELIVERY, "Mouser");

        assertEquals("Qty Shipped", detected.mapping().get(OrderFileColumn.QUANTITY));
    }

    @Test
    @DisplayName("without a recognisable header row the first row is offered for manual mapping")
    void fallsBackToFirstRow() {
        List<List<String>> records = List.of(List.of("A", "B"), List.of("x", "1"));

        OrderFileColumnMapper.Detected detected = mapper.detect(records, Kind.ORDER, "Mouser");

        assertEquals(0, detected.headerRow());
        assertEquals(List.of("A", "B"), detected.headers());
        assertTrue(detected.mapping().isEmpty());
    }
}
