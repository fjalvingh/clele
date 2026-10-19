package com.clele.parts.service.order;

import com.clele.parts.service.bom.BomFileParser;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a file a supplier hands back — Excel (.xlsx or the old .xls) or delimited text — into raw
 * rows of cell text. Nothing is interpreted here: which row is the header and what the columns
 * mean is {@link OrderFileColumnMapper}'s job.
 */
@Component
@RequiredArgsConstructor
public class OrderFileReader {

    /** "PK": every .xlsx is a zip archive. */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B};
    /** The OLE2 compound document header of an old .xls. */
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    private final BomFileParser csvParser;

    public List<List<String>> read(byte[] data) {
        if (data == null || data.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
        }
        // Sniffed from the content rather than trusted from the file name: a browser download
        // renamed by hand, or a .csv that is really HTML, is better caught here than half-parsed.
        return startsWith(data, ZIP_MAGIC) || startsWith(data, OLE2_MAGIC)
                ? readWorkbook(data)
                : csvParser.readRecords(data);
    }

    private List<List<String>> readWorkbook(byte[] data) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    cells.add(cellText(row.getCell(c), formatter, evaluator));
                }
                if (cells.stream().anyMatch(v -> v != null && !v.isBlank())) {
                    rows.add(cells);
                }
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read the file as a spreadsheet: " + e.getMessage());
        }
    }

    /**
     * A numeric cell is read as its full value, not as displayed: a price column formatted to two
     * decimals would otherwise turn a € 0.0034 resistor into € 0.00 — exactly the prices an order of
     * small parts is made of.
     */
    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        String text = formatter.formatCellValue(cell, evaluator);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
