package com.clele.parts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** The registration reply (RFC 7591 §3.2.1). Field names are the specification's, not ours. */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthRegistrationResponse {

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("client_id_issued_at")
    private long clientIdIssuedAt;

    /** Absent for a public client — the usual case, where PKCE stands in for a secret. */
    @JsonProperty("client_secret")
    private String clientSecret;

    /** 0 means "does not expire", which is what the specification says to send. */
    @JsonProperty("client_secret_expires_at")
    private Long clientSecretExpiresAt;

    @JsonProperty("redirect_uris")
    private List<String> redirectUris;

    @JsonProperty("client_name")
    private String clientName;

    @JsonProperty("grant_types")
    private List<String> grantTypes;

    @JsonProperty("response_types")
    private List<String> responseTypes;

    @JsonProperty("token_endpoint_auth_method")
    private String tokenEndpointAuthMethod;

    private String scope;
}
