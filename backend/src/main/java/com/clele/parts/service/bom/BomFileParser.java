package com.clele.parts.service.bom;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an uploaded BOM export into headers plus raw rows. Knows nothing about what the columns
 * mean — that is {@link BomColumnMapper}'s job.
 */
@Component
public class BomFileParser {

    /** Delimiters we recognise, in the order they are preferred when the counts tie. */
    private static final char[] DELIMITERS = {',', ';', '\t', '|'};

    /** U+FEFF, written as a code point so it is visible in the source rather than an invisible char. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    /** Headers plus rows keyed by header name, in file order. */
    public record ParsedFile(char delimiter, List<String> headers, List<Map<String, String>> rows) {
    }

    public ParsedFile parse(byte[] data) {
        String text = decode(data);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
        }

        char delimiter = sniffDelimiter(text);
        // Deliberately parsed without setHeader(): Commons CSV rejects a duplicate or blank header
        // name outright, and real exports carry both (two "Description" columns, a trailing empty
        // one). Reading the header row as an ordinary record lets dedupeHeaders repair it — the
        // user can then map the column they meant instead of being told the file is unreadable.
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();

        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The file has no header row");
            }

            List<String> headers = dedupeHeaders(toList(records.get(0)));
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord csvRecord : records.subList(1, records.size())) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < csvRecord.size() ? blankToNull(csvRecord.get(i)) : null);
                }
                if (row.values().stream().anyMatch(v -> v != null)) {
                    rows.add(row);
                }
            }
            return new ParsedFile(delimiter, headers, rows);
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read the file as CSV: " + e.getMessage());
        }
    }

    /**
     * UTF-8, with the byte order mark stripped. Windows and KiCad both emit a BOM on occasion, and
     * left in place it becomes part of the first header's name — so "Reference" stops matching its
     * own synonym and the whole mapping silently falls apart on exactly one column.
     */
    private String decode(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    private List<String> toList(CSVRecord csvRecord) {
        List<String> values = new ArrayList<>(csvRecord.size());
        for (int i = 0; i < csvRecord.size(); i++) {
            values.add(csvRecord.get(i));
        }
        return values;
    }

    /**
     * Picks the delimiter by counting candidates outside quotes on the header line. Counting only
     * the header avoids being misled by a comma inside a description field, and quotes are honoured
     * because a grouped designator list ("C1,C2,C3") is a quoted field containing commas.
     */
    char sniffDelimiter(String text) {
        String header = firstNonBlankLine(text);
        char best = ',';
        int bestCount = 0;
        for (char candidate : DELIMITERS) {
            int count = countOutsideQuotes(header, candidate);
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private String firstNonBlankLine(String text) {
        for (String line : text.split("\r?\n")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    /**
     * Makes header names unique and non-blank. Commons CSV rejects a duplicate header outright, and
     * exports do repeat one (two "Description" columns, or a trailing empty one) — refusing the
     * whole file over that would be unhelpful when the user can just map the column they want.
     */
    private List<String> dedupeHeaders(List<String> raw) {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String name = raw.get(i) == null || raw.get(i).isBlank()
                    ? "Column " + (i + 1)
                    : raw.get(i).trim();
            String unique = name;
            int suffix = 2;
            while (headers.contains(unique)) {
                unique = name + " (" + suffix++ + ")";
            }
            headers.add(unique);
        }
        return headers;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
