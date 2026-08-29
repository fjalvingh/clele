package com.clele.parts.dto;

import lombok.Data;

@Data
public class OAuthApproveRequest {
    /** Which organisation the client is being given access to. Defaults to the one in force. */
    private Long organisationId;
}
