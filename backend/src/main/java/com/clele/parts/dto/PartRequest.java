package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartRequest {

    @NotBlank(message = "Part number is required")
    private String partNumber;

    private String description;
    private String details;
    private String manufacturer;
    private boolean personalNumber;
    private String datasheetUrl;
    private Map<String, Object> specs;

    /**
     * How {@link #specs} combines with what the part already holds.
     *
     * <p><b>Send {@code REPLACE} only if you rendered every key the part carries.</b> A form that
     * builds its fields from {@code spec_definition} does not qualify: a part can hold keys no
     * definition covers (the AI paths keep unrecognised keys on purpose, so a later "rescan from
     * parts" can turn them into definitions), and replacing wholesale from such a form deletes them
     * on the next save. That was the behaviour before this field existed.
     *
     * <p>The default is therefore {@code MERGE}, which is safe in the direction that matters: a
     * client that forgets the flag can only fail to delete a spec, never destroy one. Under
     * {@code MERGE} a key sent with a null or blank value is removed — that is how a merging client
     * clears a field, since omitting the key means "leave it alone".
     */
    private SpecsMode specsMode = SpecsMode.MERGE;

    private Long categoryId;
    private List<String> tags;
}
