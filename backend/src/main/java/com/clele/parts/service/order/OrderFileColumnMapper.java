package com.clele.parts.service.order;

import com.clele.parts.service.bom.BomFileParser;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Finds the header row of a supplier's file and guesses what each column carries, by matching the
 * header names against synonyms — the same approach as the BOM import, for the same reason: one
 * reader covers Mouser, Farnell and the rest, and a header nobody anticipated costs the user a
 * dropdown rather than a code change.
 */
@Component
public class OrderFileColumnMapper {

    /** Which file is being read; they differ in what "the quantity" means. */
    public enum Kind {
        /** The supplier's matched version of our order: the quantity is what will be ordered. */
        ORDER,
        /** A delivery slip: the quantity is what was shipped, not what was ordered. */
        DELIVERY
    }

    /** How far down a title block may push the header row before we stop looking for it. */
    private static final int HEADER_SEARCH_ROWS = 30;

    /**
     * Exact synonyms per column, normalised (lowercase, alphanumerics only). Earlier wins when a
     * file has several candidates. Columns are resolved in declaration order and a header is
     * claimed by one column only, so the customer number is settled before "part number" can be
     * mistaken for an MPN.
     */
    private static final Map<OrderFileColumn, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put(OrderFileColumn.CUSTOMER_PART_NUMBER, List.of(
                "customerpartnumber", "customerpartno", "customerno", "customernumber", "customerpn",
                "custpartnumber", "custpartno", "customerref", "customerreference", "customer",
                "yourpartnumber", "yourreference", "linenote"));
        SYNONYMS.put(OrderFileColumn.SUPPLIER_PART_NUMBER, List.of(
                "mouserno", "mouserpartnumber", "mouserpartno", "mousernumber", "mouserpn", "mouser",
                "supplierpartnumber", "supplierpartno", "supplierpn", "distributorpartnumber",
                "distributorpartno", "ordercode", "farnellordercode", "digikeypartnumber",
                "digikeypartno", "digikeypn", "rsstockno", "rspartnumber", "stockno", "lcscpartnumber",
                "lcscpart", "lcsc", "sku"));
        SYNONYMS.put(OrderFileColumn.MPN, List.of(
                "mfrno", "mfrpartnumber", "mfrpartno", "manufacturerpartnumber", "manufacturerpartno",
                "manufacturerno", "mpn", "mfgpartnumber", "mfgpartno", "partnumber", "partno"));
        SYNONYMS.put(OrderFileColumn.MANUFACTURER, List.of(
                "manufacturer", "manufacturername", "mfrname", "mfgname", "brand", "make", "mfr"));
        SYNONYMS.put(OrderFileColumn.DESCRIPTION, List.of(
                "description", "productdescription", "desc", "customerdescription"));
        SYNONYMS.put(OrderFileColumn.QUANTITY, List.of()); // per kind, see QUANTITY_SYNONYMS
        SYNONYMS.put(OrderFileColumn.UNIT_PRICE, List.of(
                "unitprice", "price", "priceeach", "priceperunit", "pricepiece", "unitcost", "netprice"));
    }

    private static final Map<Kind, List<String>> QUANTITY_SYNONYMS = Map.of(
            Kind.ORDER, List.of(
                    "orderqty", "orderquantity", "qtyordered", "quantityordered", "quantity", "qty",
                    "qnty", "amount"),
            Kind.DELIVERY, List.of(
                    "shippedqty", "qtyshipped", "shipqty", "quantityshipped", "shipped",
                    "deliveredqty", "qtydelivered", "quantitydelivered", "delivered",
                    "quantity", "qty", "qnty"));

    /**
     * Header prefixes that also count as a price: "Price (EUR)" normalises to "priceeur", and the
     * currency is not something to enumerate. Extended/total prices are excluded — they carry the
     * line value, not the unit price.
     */
    private static final List<String> PRICE_PREFIXES = List.of("unitprice", "price");

    public record Detected(int headerRow, List<String> headers, Map<OrderFileColumn, String> mapping) {
    }

    /**
     * Finds the header row — the first row within {@link #HEADER_SEARCH_ROWS} that maps at least two
     * columns, one of which identifies a part — and the mapping on it. Without one the first row
     * is taken as the header and the mapping left as far as it got, so the user can complete it.
     */
    public Detected detect(List<List<String>> records, Kind kind, String supplierName) {
        for (int r = 0; r < Math.min(records.size(), HEADER_SEARCH_ROWS); r++) {
            List<String> headers = BomFileParser.dedupeHeaders(records.get(r));
            Map<OrderFileColumn, String> mapping = map(headers, kind, supplierName);
            if (mapping.size() >= 2 && mapping.keySet().stream().anyMatch(OrderFileColumn::identifies)) {
                return new Detected(r, headers, mapping);
            }
        }
        List<String> headers = records.isEmpty()
                ? List.of()
                : BomFileParser.dedupeHeaders(records.get(0));
        return new Detected(0, headers, map(headers, kind, supplierName));
    }

    Map<OrderFileColumn, String> map(List<String> headers, Kind kind, String supplierName) {
        Map<OrderFileColumn, String> mapping = new LinkedHashMap<>();
        Set<String> claimed = new HashSet<>();
        for (OrderFileColumn column : SYNONYMS.keySet()) {
            List<String> synonyms = synonyms(column, kind, supplierName);
            String best = null;
            int bestRank = Integer.MAX_VALUE;
            for (String header : headers) {
                if (claimed.contains(header)) {
                    continue;
                }
                int rank = rank(column, normalize(header), synonyms);
                if (rank >= 0 && rank < bestRank) {
                    best = header;
                    bestRank = rank;
                }
            }
            if (best != null) {
                mapping.put(column, best);
                claimed.add(best);
            }
        }
        return mapping;
    }

    private List<String> synonyms(OrderFileColumn column, Kind kind, String supplierName) {
        if (column == OrderFileColumn.QUANTITY) {
            return QUANTITY_SYNONYMS.get(kind);
        }
        if (column == OrderFileColumn.SUPPLIER_PART_NUMBER && supplierName != null) {
            // Our own order file heads this column "<Supplier> Part Number", so a file that went
            // out and came back unchanged maps whatever the supplier is called.
            String name = normalize(supplierName);
            List<String> withName = new ArrayList<>(List.of(
                    name + "partnumber", name + "partno", name + "no", name + "pn", name));
            withName.addAll(SYNONYMS.get(column));
            return withName;
        }
        return SYNONYMS.get(column);
    }

    private int rank(OrderFileColumn column, String header, List<String> synonyms) {
        int exact = synonyms.indexOf(header);
        if (exact >= 0) {
            return exact;
        }
        if (column == OrderFileColumn.UNIT_PRICE && !header.contains("ext") && !header.contains("total")) {
            for (String prefix : PRICE_PREFIXES) {
                if (header.startsWith(prefix)) {
                    return synonyms.size() + PRICE_PREFIXES.indexOf(prefix);
                }
            }
        }
        return -1;
    }

    /** Lowercase, alphanumerics only — so "Mfr. No:" and "MFR_NO" compare equal. */
    static String normalize(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
