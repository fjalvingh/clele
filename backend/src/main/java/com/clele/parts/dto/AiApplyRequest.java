package com.clele.parts.dto;

import lombok.*;

import java.util.Map;

/**
 * Applies a chosen AI-lookup result to an existing part — the "Look up specs" action on Part Detail.
 *
 * <p>Every field is what the user actually ticked in the confirmation step, so a null column field
 * means "leave the part's value alone" rather than "clear it". {@code specs} likewise carries only
 * the accepted entries, and is merged onto the part rather than replacing its map: the user is
 * confirming individual values, not declaring the part's whole specification.
 *
 * <p>Deliberately separate from {@link PartRequest}: that one has no partial-update semantics (an
 * absent {@code datasheetUrl} clears the column) and no way to say "keep everything I did not
 * mention". It is also separate from {@link OctopartApplyRequest}, which requires an
 * {@code octopartId} and has its own contract — merging the two would mean one method with a
 * sometimes-required id, which is how the wrong field gets written.
 *
 * <p>Note there is no category: the lookup returns a category <em>name</em>, and resolving a name to
 * one of this organisation's categories is a separate, fuzzy problem. The name is shown in the modal
 * as context and not applied.
 *
 * <p>Despite the name this is the apply path for every "a source proposed this, the user ticked
 * some of it" flow — the web lookup, the datasheet reader and the component cache — because all
 * three answer the same question and must resolve it identically.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiApplyRequest {

    private String description;
    private String manufacturer;
    private String mpn;
    private String datasheetUrl;

    /**
     * The package/case, e.g. {@code SOIC-8}. The web lookup does not return one, which is why this
     * arrived late; the component cache does, as a first-class column on every row, and dropping it
     * would mean the one source that reliably knows the footprint could not set it.
     */
    private String footprint;

    /**
     * The long free-text description. Only the datasheet reader fills this — the web lookup returns
     * a one-line {@code shortDescription}, which belongs in {@code description}, while a datasheet
     * carries several sentences of what the part actually does.
     */
    private String details;

    /** The accepted specs, merged onto the part's existing map. */
    private Map<String, Object> specs;
}
