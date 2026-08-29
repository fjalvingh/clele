package com.clele.parts.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * A client registering itself (RFC 7591). Clients send a good deal more metadata than this app has
 * any use for — logo URIs, contacts, policy URLs — so unknown fields are ignored rather than
 * rejected: refusing a registration over a field we do not read would break clients for no gain.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OAuthRegistrationRequest {

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
