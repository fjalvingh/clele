package com.clele.parts.service.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BomFileParserTest {

    private final BomFileParser parser = new BomFileParser();

    private BomFileParser.ParsedFile parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a quoted designator list keeps its commas instead of splitting into columns")
    void keepsQuotedDesignatorList() {
        // Every grouped BOM line is a quoted field full of the delimiter. Getting this wrong
        // shifts every later column by two and the whole mapping silently reads the wrong data.
        BomFileParser.ParsedFile parsed = parse("""
                Reference,Value,Qty
                "C1,C2,C3",100nF,3
                R1,10k,1
                """);

        assertEquals(2, parsed.rows().size());
        assertEquals("C1,C2,C3", parsed.rows().get(0).get("Reference"));
        assertEquals("100nF", parsed.rows().get(0).get("Value"));
        assertEquals("3", parsed.rows().get(0).get("Qty"));
    }

    @Test
    @DisplayName("a semicolon-delimited export is detected, not read as one giant column")
    void sniffsSemicolonDelimiter() {
        BomFileParser.ParsedFile parsed = parse("""
                Reference;Value;Qty
                C1;100nF;1
                """);

        assertEquals(';', parsed.delimiter());
        assertEquals(3, parsed.headers().size());
        assertEquals("100nF", parsed.rows().get(0).get("Value"));
    }

    @Test
    @DisplayName("a tab-delimited export is detected")
    void sniffsTabDelimiter() {
        BomFileParser.ParsedFile parsed = parse("Reference\tValue\tQty\nC1\t100nF\t1\n");

        assertEquals('\t', parsed.delimiter());
        assertEquals("100nF", parsed.rows().get(0).get("Value"));
    }

    @Test
    @DisplayName("a comma inside a quoted description does not outvote the real delimiter")
    void quotedCommasDoNotDecideTheDelimiter() {
        BomFileParser.ParsedFile parsed = parse("""
                Reference;"Description, long";Qty
                C1;"Capacitor, ceramic, 100nF";1
                """);

        assertEquals(';', parsed.delimiter());
        assertEquals("Capacitor, ceramic, 100nF", parsed.rows().get(0).get("Description, long"));
    }

    @Test
    @DisplayName("a UTF-8 byte order mark is stripped, so the first header still matches its synonym")
    void stripsByteOrderMark() {
        // Left in place the BOM becomes part of "Reference", which then matches no synonym — one
        // column silently fails to map and nothing says why.
        BomFileParser.ParsedFile parsed = parse("﻿Reference,Value\nC1,100nF\n");

        assertEquals("Reference", parsed.headers().get(0));
    }

    @Test
    @DisplayName("duplicate and blank headers are repaired rather than rejecting the file")
    void repairsAwkwardHeaders() {
        // Commons CSV refuses both outright. Real exports carry them, and the user can still map
        // the column they meant — so refusing the whole file would be unhelpful.
        BomFileParser.ParsedFile parsed = parse("""
                Reference,Description,Description,,Qty
                C1,a,b,c,1
                """);

        assertEquals(java.util.List.of("Reference", "Description", "Description (2)", "Column 4", "Qty"),
                parsed.headers());
        assertEquals("a", parsed.rows().get(0).get("Description"));
        assertEquals("b", parsed.rows().get(0).get("Description (2)"));
    }

    @Test
    @DisplayName("blank cells arrive as null and fully blank rows are dropped")
    void normalisesEmptyCells() {
        BomFileParser.ParsedFile parsed = parse("""
                Reference,Value,MPN
                C1,100nF,
                ,,
                R1,10k,RC0805
                """);

        assertEquals(2, parsed.rows().size());
        assertNull(parsed.rows().get(0).get("MPN"));
        assertEquals("RC0805", parsed.rows().get(1).get("MPN"));
    }

    @Test
    @DisplayName("a short row does not throw — the missing trailing columns read as null")
    void toleratesShortRows() {
        BomFileParser.ParsedFile parsed = parse("""
                Reference,Value,Qty
                C1,100nF
                """);

        assertEquals("100nF", parsed.rows().get(0).get("Value"));
        assertNull(parsed.rows().get(0).get("Qty"));
    }

    @Test
    @DisplayName("an empty file is refused with a message, not a stack trace")
    void refusesEmptyFile() {
        assertThrows(ResponseStatusException.class, () -> parse("   "));
    }
}
