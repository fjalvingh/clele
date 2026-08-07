package com.clele.parts.service.bom;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Guesses which column of an uploaded BOM file carries which {@link BomColumnRole}, by matching the
 * header names against known synonyms.
 *
 * <p>Detection is a starting point, never the last word: the import returns the mapping it guessed
 * <em>and</em> every header in the file, so the user can correct it before committing. That is what
 * makes one generic parser cover KiCad, Eagle, Altium and distributor exports without a code change
 * per vendor — a header nobody anticipated costs the user one dropdown, not a bug report.
 */
@Component
public class BomColumnMapper {

    /**
     * Synonyms per role, already normalised (lowercase, alphanumerics only) so "Mfr. Part #" and
     * "mfrpartnumber" are the same entry. Order within a role is the preference order when a file
     * carries more than one candidate — the earlier synonym wins.
     *
     * <p>Roles are resolved in declaration order, and a column is claimed by at most one role, so
     * an ambiguous header like "comment" (Altium's value column) goes to VALUE rather than
     * DESCRIPTION because VALUE is declared first.
     */
    private static final Map<BomColumnRole, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put(BomColumnRole.REFERENCES, List.of(
                "reference", "references", "ref", "refs", "refdes", "designator", "designators",
                "referencedesignator", "referencedesignators", "partreference"));
        SYNONYMS.put(BomColumnRole.QUANTITY, List.of(
                "qty", "quantity", "qnty", "qtyperboard", "quantityperboard", "count", "amount"));
        SYNONYMS.put(BomColumnRole.MPN, List.of(
                "mpn", "manufacturerpartnumber", "manufacturerpartno", "mfrpartnumber", "mfrpart",
                "mfrpartno", "mfgpartnumber", "manufacturerpart", "partnumber", "partno",
                "manufacturer part number", "vendorpartnumber", "orderingcode"));
        // Deliberately excludes "vendor" and "supplier": those name the distributor, not the maker.
        // Claiming them would write "JLCPCB" into the manufacturer field of every part on the
        // board — wrong data, arrived at silently. Left unmapped they are kept in `extra` instead,
        // and the user can map one explicitly if their export really does mean the manufacturer.
        SYNONYMS.put(BomColumnRole.MANUFACTURER, List.of(
                "manufacturer", "manufacturername", "mfr", "mfg", "make", "brand"));
        SYNONYMS.put(BomColumnRole.VALUE, List.of(
                "value", "val", "comment", "cmpname", "componentname"));
        SYNONYMS.put(BomColumnRole.FOOTPRINT, List.of(
                "footprint", "package", "pcbfootprint", "pattern", "case", "land pattern",
                "landpattern"));
        SYNONYMS.put(BomColumnRole.DESCRIPTION, List.of(
                "description", "desc", "notes", "note", "comments"));
        SYNONYMS.put(BomColumnRole.DATASHEET, List.of(
                "datasheet", "datasheeturl", "documentation", "doc"));
        SYNONYMS.put(BomColumnRole.DNP, List.of(
                "dnp", "donotpopulate", "dontpopulate", "donotplace", "nofit", "nostuff", "exclude",
                "excludefrombom", "excludefromboard", "fitted", "populate"));
    }

    /**
     * Values in a DNP-style column that mean "not fitted". Note the column may be phrased either
     * way round — "DNP=yes" and "Fitted=no" say the same thing — which is why
     * {@link #isDoNotPopulate(String, String)} takes the header too.
     */
    private static final Set<String> TRUTHY = Set.of("1", "true", "yes", "y", "x", "dnp", "on");

    /** Headers whose sense is inverted: a truthy value there means the part IS fitted. */
    private static final Set<String> INVERTED_DNP_HEADERS = Set.of("fitted", "populate");

    /** Guesses a role for each header. Headers claimed by no role are simply absent from the map. */
    public Map<BomColumnRole, String> detect(List<String> headers) {
        Map<BomColumnRole, String> mapping = new LinkedHashMap<>();
        Set<String> claimed = new java.util.HashSet<>();

        for (Map.Entry<BomColumnRole, List<String>> entry : SYNONYMS.entrySet()) {
            String best = null;
            int bestRank = Integer.MAX_VALUE;
            for (String header : headers) {
                if (header == null || claimed.contains(header)) {
                    continue;
                }
                int rank = entry.getValue().indexOf(normalize(header));
                if (rank >= 0 && rank < bestRank) {
                    best = header;
                    bestRank = rank;
                }
            }
            if (best != null) {
                mapping.put(entry.getKey(), best);
                claimed.add(best);
            }
        }
        return mapping;
    }

    /**
     * Reads a DNP column's value as a boolean, honouring the inverted phrasings. An unrecognised
     * value reads as "fitted" — an import must not silently exclude lines it did not understand.
     */
    public boolean isDoNotPopulate(String header, String value) {
        if (value == null) {
            return false;
        }
        boolean truthy = TRUTHY.contains(value.trim().toLowerCase(Locale.ROOT));
        return INVERTED_DNP_HEADERS.contains(normalize(header)) ? !truthy : truthy;
    }

    /** Lowercase, alphanumerics only — so "Mfr. Part #" and "MFR_PART_NO" compare equal. */
    static String normalize(String header) {
        if (header == null) {
            return "";
        }
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
