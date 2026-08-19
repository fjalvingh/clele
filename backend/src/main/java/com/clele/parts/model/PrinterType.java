package com.clele.parts.model;

/**
 * Printer family a daemon drives. It decides both how the daemon reaches the printer and where the
 * label size comes from.
 */
public enum PrinterType {
    /** Network Brother QL: status over IPP, raster over raw TCP 9100. Reports its own media. */
    BROTHER_QL,
    /**
     * USB Dymo LabelWriter behind the local CUPS queue, IPP for everything. Cannot sense which
     * roll is loaded, so its label size is picked by the user.
     */
    DYMO_CUPS;

    /** Parses a reported/requested name, or null when it is blank or unknown. */
    public static PrinterType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PrinterType t : values()) {
            if (t.name().equalsIgnoreCase(name.trim())) {
                return t;
            }
        }
        return null;
    }
}
