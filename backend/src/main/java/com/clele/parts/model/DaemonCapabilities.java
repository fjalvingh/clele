package com.clele.parts.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a daemon discovered about the printers available on the machine it runs on, stored as JSONB
 * against the daemon and used to populate the queue and label-size pickers in the web app.
 *
 * <p>Each queue carries its own media list so a single report fills in every picker: otherwise the
 * UI would need a fresh daemon round trip — up to a full 25-second poll window — after each of
 * "pick a type", "pick a queue" and "pick a size".
 *
 * <p>Only printer families with something to discover report this; a network printer addressed by
 * an IP the user types has no local list, and leaves it null.
 */
public record DaemonCapabilities(List<Queue> queues) {

    public record Queue(String name, String description, String makeAndModel, List<Media> media) {}

    /**
     * One label stock the queue offers. Printable dimensions already have the printer's unmarkable
     * margins subtracted, so the frontend renders straight to them.
     */
    public record Media(String keyword,
                        String displayName,
                        BigDecimal widthMm,
                        BigDecimal lengthMm,
                        BigDecimal printableWidthMm,
                        BigDecimal printableLengthMm) {}
}
