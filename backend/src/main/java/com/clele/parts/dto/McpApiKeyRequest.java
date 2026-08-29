package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class McpApiKeyRequest {

    /** What the key is for, in the owner's words ("Claude Desktop on the laptop"). */
    @NotBlank(message = "A name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    /** The organisation the key may read. Defaults to the one in force when omitted. */
    private Long organisationId;
}
