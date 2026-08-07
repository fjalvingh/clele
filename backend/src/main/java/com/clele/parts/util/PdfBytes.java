package com.clele.parts.util;

/**
 * Is this actually a PDF?
 *
 * <p>Worth asking on every downloaded datasheet, because the failure mode is not a 404. A vendor
 * that has moved or retired a document commonly answers with HTTP 200 and an HTML landing page,
 * and a URL ending in {@code .pdf} says nothing about what came back. Stored unchecked, that page
 * becomes a "datasheet" attachment that only reveals itself when somebody opens it — or worse,
 * when spec extraction reads it.
 */
public final class PdfBytes {

    /** Bytes every PDF starts with. */
    private static final byte[] MAGIC = {'%', 'P', 'D', 'F'};

    /** How far in to look for the header — some servers prepend whitespace or a BOM. */
    private static final int SCAN_LIMIT = 1024;

    private PdfBytes() {}

    public static boolean looksLikePdf(byte[] data) {
        if (data == null || data.length < MAGIC.length) {
            return false;
        }
        int limit = Math.min(data.length - MAGIC.length, SCAN_LIMIT);
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < MAGIC.length; j++) {
                if (data[i + j] != MAGIC[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
