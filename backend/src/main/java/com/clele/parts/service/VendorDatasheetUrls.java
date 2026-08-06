package com.clele.parts.service;

import com.clele.parts.model.Part;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guesses the manufacturer's own URL for a part's datasheet.
 *
 * <p>This is the primary way to repair a dead datasheet link, and it exists because searching is
 * not available in bulk: DuckDuckGo answers automated searches with a CAPTCHA ("select all squares
 * containing a duck", served as HTTP <em>202</em>, so it looks like a successful empty result). A
 * vendor whose URLs are predictable can be asked directly — no scraping, no rate limit, and the
 * document that comes back is the manufacturer's own rather than a broker's re-host.
 *
 * <p><b>Candidates are guesses and must be verified.</b> TI answers an unknown part with HTTP 200
 * and an HTML landing page, not a 404 — {@code sn74ls76a} does exactly that — so a caller must
 * check the bytes really are a PDF and really concern this part before storing the URL.
 *
 * <p>The suffix trimming is deliberately dumb: rather than model each vendor's package-code
 * vocabulary (N, J, DW, PW, NS, E, DT, reel suffix R, temperature grades …), it walks the part
 * number back one character at a time and lets verification reject the misses. On this catalogue
 * that resolves {@code TLC274CN} → {@code tlc274}, {@code LM324PWR} → {@code lm324},
 * {@code SN74LVTH541DWR} → {@code sn74lvth541} and {@code LM1117DT-2.5/NOPB} → {@code lm1117}
 * without a single package-code rule.
 */
public final class VendorDatasheetUrls {

    /** Cap on generated candidates — each one costs an HTTP request. Six covers every case seen. */
    private static final int MAX_CANDIDATES = 6;

    /** Below this a trimmed stem stops identifying a part ("sn74" matches half of TI's catalogue). */
    private static final int MIN_STEM = 5;

    private VendorDatasheetUrls() {}

    /** Candidate datasheet URLs for the part, best guess first; empty when the vendor is unknown. */
    public static List<String> candidatesFor(Part part) {
        String manufacturer = part.getManufacturer();
        if (manufacturer == null) {
            return List.of();
        }
        String mfr = manufacturer.toLowerCase(Locale.ROOT);
        String number = (part.getMpn() != null && !part.getMpn().isBlank())
                ? part.getMpn() : part.getPartNumber();
        if (number == null || number.isBlank()) {
            return List.of();
        }

        if (mfr.contains("texas instruments")) {
            return stems(number).stream()
                    .map(s -> "https://www.ti.com/lit/ds/symlink/" + s + ".pdf")
                    .toList();
        }
        return List.of();
    }

    /**
     * The part number reduced to a URL stem, then progressively shortened. Everything from a
     * {@code /} onwards is an ordering suffix ({@code /NOPB}) rather than part of the number, and
     * TI's military {@code SNJ} prefix maps to the commercial {@code SN} its datasheets are filed
     * under.
     */
    static List<String> stems(String partNumber) {
        String cleaned = partNumber.toUpperCase(Locale.ROOT);
        int slash = cleaned.indexOf('/');
        if (slash > 0) {
            cleaned = cleaned.substring(0, slash);
        }
        cleaned = cleaned.replaceAll("[^A-Z0-9]", "");
        if (cleaned.startsWith("SNJ")) {
            cleaned = "SN" + cleaned.substring(3);
        }
        cleaned = cleaned.toLowerCase(Locale.ROOT);

        Set<String> out = new LinkedHashSet<>();
        for (int len = cleaned.length(); len >= MIN_STEM && out.size() < MAX_CANDIDATES; len--) {
            out.add(cleaned.substring(0, len));
        }
        return new ArrayList<>(out);
    }
}
