package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One attribute of a cached component, already translated into this app's terms.
 *
 * <p>Both names are carried because they answer different questions. {@code key} is where the value
 * will land in {@code part.specs} and is what the apply call sends back; {@code sourceName} is what
 * the cache calls it, which is the only way to see <em>why</em> a value landed under a given key
 * when the two do not obviously correspond.
 */
@Data
@Builder
public class ComponentCacheSpecDTO {

    /** The {@code part.specs} key: a spec definition's {@code jsonName}, or a new key derived from
     *  {@code sourceName} when this organisation has no field for it yet. */
    private String key;

    /** The attribute's name in the cache, e.g. {@code "Gain Bandwidth Product"}. */
    private String sourceName;

    /** The value as it will be stored. */
    private String value;

    /** Whether {@code key} matched an existing spec definition (directly or through an alias). */
    private boolean known;
}
