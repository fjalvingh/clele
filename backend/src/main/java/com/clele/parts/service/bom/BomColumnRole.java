package com.clele.parts.service.bom;

/**
 * The meaning a column of an uploaded BOM file carries. Every export names its columns differently
 * — KiCad writes "Reference", Altium "Designator", a distributor "Ref Des" — so the importer maps
 * headers onto these roles rather than recognising a fixed layout.
 *
 * <p>A column claimed by no role is not discarded: it is kept verbatim in
 * {@code project_bom_line.extra}.
 */
public enum BomColumnRole {

    /** The designators: "C1,C2,C3". The line's identity, and the merge key on re-import. */
    REFERENCES,

    /** The schematic value: "100nF", "10k", "LM317". Often the only clue to what the part is. */
    VALUE,

    FOOTPRINT,

    /** Quantity per board. Absent in many exports — then the designator count stands in. */
    QUANTITY,

    /** Manufacturer part number: the best key to match against the catalogue when present. */
    MPN,

    MANUFACTURER,

    DESCRIPTION,

    DATASHEET,

    /** Do not populate. Lands the line on {@code BomLineStatus.EXCLUDED}. */
    DNP
}
