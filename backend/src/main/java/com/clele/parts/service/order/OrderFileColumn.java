package com.clele.parts.service.order;

/** What a column of a supplier's file can carry. */
public enum OrderFileColumn {
    /** The supplier's own number for the part (Mouser No., Farnell order code, …). */
    SUPPLIER_PART_NUMBER,
    MPN,
    MANUFACTURER,
    /** Our part number, which the order file sends out and the supplier sends back. */
    CUSTOMER_PART_NUMBER,
    DESCRIPTION,
    /** Ordered quantity in a matched order; shipped quantity on a delivery slip. */
    QUANTITY,
    UNIT_PRICE;

    /** The columns that can identify a row's part. A header row has at least one of them. */
    public boolean identifies() {
        return this == SUPPLIER_PART_NUMBER || this == MPN || this == CUSTOMER_PART_NUMBER;
    }
}
