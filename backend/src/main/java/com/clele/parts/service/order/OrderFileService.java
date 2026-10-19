package com.clele.parts.service.order;

import com.clele.parts.dto.OrderFilePreviewDTO;
import com.clele.parts.dto.PurchaseOrderLineDTO;
import com.clele.parts.model.*;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.PartSupplierRepository;
import com.clele.parts.repository.PurchaseOrderLineRepository;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.order.OrderFileColumnMapper.Kind;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * The files exchanged with a supplier: the order file we upload to them, and the two files that
 * come back — their matched version of the order, and the delivery slip.
 *
 * <p>Our part number travels out in the <i>customer part number</i> column (Mouser's BOM tool keeps
 * it, as do most distributors' upload tools) and comes back in their file, so the returned rows can
 * be put back on the right line exactly even when the supplier changed everything else about them.
 * Their part number and the MPN are the fallbacks for a file where it got lost.
 *
 * <p>Reading a file stores nothing. The preview goes to the SPA, the user resolves what did not
 * match, and the outcome is an ordinary replace-lines or receive request — so there is no
 * half-imported state to clean up, and the user's corrections need no second upload.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderFileService {

    private final PurchaseOrderService orderService;
    private final PurchaseOrderLineRepository lineRepository;
    private final PartSupplierRepository partSupplierRepository;
    private final PartRepository partRepository;
    private final OrderFileReader reader;
    private final OrderFileColumnMapper mapper;
    private final CurrentOrganisationService currentOrganisationService;

    public record ExportedFile(String filename, byte[] data) {
    }

    /**
     * The order as a spreadsheet to upload at the supplier: one row per line. Every cell is text,
     * so Excel does not turn a part number like "0805-10K" into a date or strip a leading zero.
     */
    public ExportedFile export(Long orderId) {
        PurchaseOrder order = orderService.require(orderId);
        List<PurchaseOrderLine> lines = lineRepository.findByOrderIdWithPart(orderId);
        String supplier = order.getSupplier().getName();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Order " + order.getId());
            CellStyle bold = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            bold.setFont(font);

            String[] headers = {supplier + " Part Number", "Manufacturer Part Number", "Manufacturer",
                    "Customer Part Number", "Quantity", "Description"};
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(bold);
            }
            int r = 1;
            for (PurchaseOrderLine line : lines) {
                Part part = line.getPart();
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(nullToEmpty(line.getSupplierPartNumber()));
                row.createCell(1).setCellValue(nullToEmpty(part.getMpn()));
                row.createCell(2).setCellValue(nullToEmpty(part.getManufacturer()));
                row.createCell(3).setCellValue(part.getPartNumber());
                row.createCell(4).setCellValue(line.getQuantity());
                row.createCell(5).setCellValue(nullToEmpty(part.getDescription()));
            }
            for (int c = 0; c < headers.length; c++) {
                sheet.autoSizeColumn(c);
            }
            workbook.write(out);
            String filename = "order-" + order.getId() + "-" + supplier.replaceAll("[^A-Za-z0-9]+", "-") + ".xlsx";
            return new ExportedFile(filename, out.toByteArray());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write the order file: " + e.getMessage());
        }
    }

    /**
     * Reads a supplier file against an order. {@code ORDER} files are read against a draft and may
     * name parts not on it yet (a substitute, a line added at the supplier) — those are looked up in
     * the catalogue. {@code DELIVERY} files are read against a placed order and only ever match its
     * own lines.
     *
     * @param overrides column → header chosen by the user; replaces the detected mapping entirely
     */
    public OrderFilePreviewDTO preview(Long orderId, byte[] data, Kind kind, Map<String, String> overrides) {
        PurchaseOrder order = kind == Kind.ORDER
                ? orderService.requireStatus(orderId, PurchaseOrderStatus.DRAFT, "take the supplier's file")
                : orderService.requireStatus(orderId, PurchaseOrderStatus.ORDERED, "receive a delivery");
        List<List<String>> records = reader.read(data);
        OrderFileColumnMapper.Detected detected = mapper.detect(records, kind, order.getSupplier().getName());
        Map<OrderFileColumn, String> mapping = overrides == null ? detected.mapping() : parseMapping(overrides, detected.headers());

        LineIndex index = new LineIndex(lineRepository.findByOrderIdWithPart(orderId),
                partSupplierRepository, order.getSupplier().getId());
        Set<Long> claimed = new HashSet<>();
        Map<Long, Integer> firstRowOfLine = new HashMap<>();
        List<OrderFilePreviewDTO.Row> rows = new ArrayList<>();

        List<String> headers = detected.headers();
        for (int r = detected.headerRow() + 1; r < records.size(); r++) {
            Map<OrderFileColumn, String> cells = cells(records.get(r), headers, mapping);
            if (cells.values().stream().allMatch(Objects::isNull)) {
                continue;
            }
            OrderFilePreviewDTO.Row row = OrderFilePreviewDTO.Row.builder()
                    .rowNumber(r + 1)
                    .supplierPartNumber(cells.get(OrderFileColumn.SUPPLIER_PART_NUMBER))
                    .mpn(cells.get(OrderFileColumn.MPN))
                    .manufacturer(cells.get(OrderFileColumn.MANUFACTURER))
                    .customerPartNumber(cells.get(OrderFileColumn.CUSTOMER_PART_NUMBER))
                    .description(cells.get(OrderFileColumn.DESCRIPTION))
                    .quantity(parseQuantity(cells.get(OrderFileColumn.QUANTITY)))
                    .unitPrice(parsePrice(cells.get(OrderFileColumn.UNIT_PRICE)))
                    .build();
            // A total row at the bottom identifies no part; it is not a line of the order.
            if (row.getSupplierPartNumber() == null && row.getMpn() == null && row.getCustomerPartNumber() == null) {
                continue;
            }
            match(row, index, kind);
            if (row.getLineId() != null) {
                Integer first = firstRowOfLine.putIfAbsent(row.getLineId(), row.getRowNumber());
                if (first != null) {
                    row.setWarning(appendWarning(row.getWarning(), "Same part as row " + first
                            + (kind == Kind.ORDER ? "; the quantities will be added" : "")));
                }
                claimed.add(row.getLineId());
            }
            rows.add(row);
        }

        List<PurchaseOrderLineDTO> missing = index.lines.stream()
                .filter(l -> !claimed.contains(l.getId()))
                .filter(l -> kind == Kind.ORDER || l.outstanding() > 0)
                .map(l -> orderService.toLineDTO(l, List.of(), null))
                .toList();

        Map<String, String> mappingOut = new LinkedHashMap<>();
        mapping.forEach((column, header) -> mappingOut.put(column.name(), header));
        return OrderFilePreviewDTO.builder()
                .headers(headers)
                .mapping(mappingOut)
                .rows(rows)
                .missingLines(missing)
                .build();
    }

    /** Customer part number first (it is ours, so exact), then the supplier's number, then the MPN. */
    private void match(OrderFilePreviewDTO.Row row, LineIndex index, Kind kind) {
        PurchaseOrderLine line = null;
        String by = null;
        if (row.getCustomerPartNumber() != null) {
            line = index.unique(index.byPartNumber, row.getCustomerPartNumber());
            by = "CUSTOMER_PART_NUMBER";
        }
        if (line == null && row.getSupplierPartNumber() != null) {
            line = index.unique(index.bySupplierPartNumber, row.getSupplierPartNumber());
            by = "SUPPLIER_PART_NUMBER";
        }
        if (line == null && row.getMpn() != null) {
            line = index.unique(index.byMpn, row.getMpn());
            by = "MPN";
        }

        if (line != null) {
            Part part = line.getPart();
            row.setLineId(line.getId());
            row.setPartId(part.getId());
            row.setPartNumber(part.getPartNumber());
            row.setPartDescription(part.getDescription());
            row.setMatchedBy(by);
            if (row.getMpn() != null && part.getMpn() != null && !part.getMpn().equalsIgnoreCase(row.getMpn())) {
                row.setWarning(appendWarning(row.getWarning(), "The file says MPN " + row.getMpn()
                        + ", the part has " + part.getMpn()));
            }
            if (kind == Kind.DELIVERY && row.getQuantity() != null && row.getQuantity() > line.outstanding()) {
                row.setWarning(appendWarning(row.getWarning(), "Only " + line.outstanding() + " still to come"));
            }
        } else if (kind == Kind.ORDER) {
            matchInCatalogue(row);
        }
        if (row.getQuantity() == null) {
            row.setWarning(appendWarning(row.getWarning(), "No quantity"));
        }
    }

    /** A part the order does not have yet: accepted only on an exact, unambiguous hit. */
    private void matchInCatalogue(OrderFilePreviewDTO.Row row) {
        Long orgId = currentOrganisationService.currentId();
        List<Part> hits = List.of();
        if (row.getCustomerPartNumber() != null) {
            hits = partRepository.findByOrganisationIdAndPartNumberIgnoreCase(orgId, row.getCustomerPartNumber());
        }
        if (hits.size() != 1 && row.getMpn() != null) {
            hits = partRepository.findByOrganisationIdAndMpnIgnoreCase(orgId, row.getMpn());
        }
        if (hits.size() == 1) {
            Part part = hits.get(0);
            row.setPartId(part.getId());
            row.setPartNumber(part.getPartNumber());
            row.setPartDescription(part.getDescription());
            row.setMatchedBy("CATALOGUE");
            row.setWarning(appendWarning(row.getWarning(), "Not on the order yet; it will be added"));
        }
    }

    /** The order's lines, indexed by every key a supplier file can name them by. */
    private static final class LineIndex {
        final List<PurchaseOrderLine> lines;
        final Map<String, List<PurchaseOrderLine>> byPartNumber = new HashMap<>();
        final Map<String, List<PurchaseOrderLine>> bySupplierPartNumber = new HashMap<>();
        final Map<String, List<PurchaseOrderLine>> byMpn = new HashMap<>();

        LineIndex(List<PurchaseOrderLine> lines, PartSupplierRepository partSuppliers, Long supplierId) {
            this.lines = lines;
            for (PurchaseOrderLine line : lines) {
                Part part = line.getPart();
                add(byPartNumber, part.getPartNumber(), line);
                add(byMpn, part.getMpn(), line);
                String spn = line.getSupplierPartNumber();
                if (spn == null) {
                    spn = partSuppliers.findByPartIdAndSupplierId(part.getId(), supplierId)
                            .map(PartSupplier::getSupplierPartNumber).orElse(null);
                }
                add(bySupplierPartNumber, spn, line);
            }
        }

        private static void add(Map<String, List<PurchaseOrderLine>> map, String key, PurchaseOrderLine line) {
            if (key != null && !key.isBlank()) {
                map.computeIfAbsent(normalize(key), k -> new ArrayList<>()).add(line);
            }
        }

        PurchaseOrderLine unique(Map<String, List<PurchaseOrderLine>> map, String key) {
            List<PurchaseOrderLine> hits = map.get(normalize(key));
            return hits != null && hits.size() == 1 ? hits.get(0) : null;
        }

        /** Case and surrounding space do not make a different part number; inner characters do. */
        private static String normalize(String key) {
            return key.trim().toUpperCase(Locale.ROOT);
        }
    }

    private Map<OrderFileColumn, String> parseMapping(Map<String, String> overrides, List<String> headers) {
        Map<OrderFileColumn, String> mapping = new LinkedHashMap<>();
        overrides.forEach((column, header) -> {
            if (header == null || header.isBlank()) {
                return;
            }
            OrderFileColumn role;
            try {
                role = OrderFileColumn.valueOf(column);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown column: " + column);
            }
            if (!headers.contains(header)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file has no column '" + header + "'");
            }
            mapping.put(role, header);
        });
        return mapping;
    }

    private static Map<OrderFileColumn, String> cells(List<String> record, List<String> headers,
                                                       Map<OrderFileColumn, String> mapping) {
        Map<OrderFileColumn, String> cells = new EnumMap<>(OrderFileColumn.class);
        mapping.forEach((column, header) -> {
            int i = headers.indexOf(header);
            String value = i >= 0 && i < record.size() ? record.get(i) : null;
            cells.put(column, value == null || value.isBlank() ? null : value.trim());
        });
        return cells;
    }

    /** "1,000" and "1.000" are both a thousand: a quantity has no fraction, so every separator goes. */
    static Integer parseQuantity(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a price written either way round: "€ 1,234.56", "1.234,56", "0,0034". When both
     * separators appear the last one is the decimal point; a lone comma is one too, since a
     * thousands separator in a unit price of a part is rarer than a European decimal comma.
     */
    static BigDecimal parsePrice(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("[^0-9.,-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        int lastDot = cleaned.lastIndexOf('.');
        int lastComma = cleaned.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            char decimal = lastDot > lastComma ? '.' : ',';
            char grouping = decimal == '.' ? ',' : '.';
            cleaned = cleaned.replace(String.valueOf(grouping), "").replace(decimal, '.');
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String appendWarning(String existing, String warning) {
        return existing == null ? warning : existing + ". " + warning;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
