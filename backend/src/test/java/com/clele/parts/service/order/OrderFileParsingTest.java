package com.clele.parts.service.order;

import com.clele.parts.service.bom.BomFileParser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderFileParsingTest {

    private final OrderFileReader reader = new OrderFileReader(new BomFileParser());

    @Test
    @DisplayName("prices are read in either decimal convention")
    void parsesPrices() {
        assertEquals(new BigDecimal("0.0034"), OrderFileService.parsePrice("0.0034"));
        assertEquals(new BigDecimal("0.0034"), OrderFileService.parsePrice("€ 0,0034"));
        assertEquals(new BigDecimal("1234.56"), OrderFileService.parsePrice("1,234.56"));
        assertEquals(new BigDecimal("1234.56"), OrderFileService.parsePrice("1.234,56 EUR"));
        assertNull(OrderFileService.parsePrice("n/a"));
        assertNull(OrderFileService.parsePrice(null));
    }

    @Test
    @DisplayName("quantities ignore grouping separators")
    void parsesQuantities() {
        assertEquals(1000, OrderFileService.parseQuantity("1,000"));
        assertEquals(1000, OrderFileService.parseQuantity("1.000"));
        assertEquals(25, OrderFileService.parseQuantity("25"));
        assertNull(OrderFileService.parseQuantity("-"));
    }

    @Test
    @DisplayName("an .xlsx price cell is read at full precision, not as its two-decimal display")
    void readsXlsxAtFullPrecision() throws Exception {
        byte[] data;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Mouser No");
            header.createCell(1).setCellValue("Price (EUR)");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("71-CRCW0805-10K");
            Cell price = row.createCell(1);
            price.setCellValue(0.0034);
            CellStyle twoDecimals = workbook.createCellStyle();
            twoDecimals.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            price.setCellStyle(twoDecimals);
            workbook.write(out);
            data = out.toByteArray();
        }

        List<List<String>> rows = reader.read(data);

        assertEquals(List.of("Mouser No", "Price (EUR)"), rows.get(0));
        assertEquals("0.0034", rows.get(1).get(1));
    }

    @Test
    @DisplayName("a delimited text file is read by the same entry point")
    void readsCsv() {
        List<List<String>> rows = reader.read("Mouser No;Qty\n71-ABC;10\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(List.of("Mouser No", "Qty"), List.of("71-ABC", "10")), rows);
    }
}
