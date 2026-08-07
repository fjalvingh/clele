package com.clele.parts.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBytesTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a normal PDF header is recognised")
    void recognisesPdf() {
        assertTrue(PdfBytes.looksLikePdf(bytes("%PDF-1.7\n%âãÏÓ\nrest of the file")));
    }

    @Test
    @DisplayName("a header behind leading whitespace or a BOM is still found")
    void toleratesLeadingJunk() {
        assertTrue(PdfBytes.looksLikePdf(bytes("﻿\r\n   %PDF-1.4 ...")));
    }

    @Test
    @DisplayName("an HTML page is rejected — this is the real failure mode, not a 404")
    void rejectsHtmlErrorPage() {
        // Vendors answer a moved or retired document with HTTP 200 and a landing page. Measured
        // against ti.com/product/LM317, which returns 358 kB of text/html for a URL that reads
        // like a document.
        assertFalse(PdfBytes.looksLikePdf(bytes(
                "<!DOCTYPE html><html><head><title>LM317 | TI.com</title></head><body>…</body></html>")));
    }

    @Test
    @DisplayName("empty, null and too-short inputs are rejected rather than throwing")
    void rejectsDegenerateInput() {
        assertFalse(PdfBytes.looksLikePdf(null));
        assertFalse(PdfBytes.looksLikePdf(new byte[0]));
        assertFalse(PdfBytes.looksLikePdf(bytes("%PD")));
    }

    @Test
    @DisplayName("a header pushed past the scan window is not searched for indefinitely")
    void stopsScanningAfterTheLimit() {
        assertFalse(PdfBytes.looksLikePdf(bytes(" ".repeat(4096) + "%PDF-1.7")));
    }
}
