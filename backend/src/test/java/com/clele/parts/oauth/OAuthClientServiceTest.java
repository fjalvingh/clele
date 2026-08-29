package com.clele.parts.oauth;

import com.clele.parts.dto.OAuthRegistrationRequest;
import com.clele.parts.dto.OAuthRegistrationResponse;
import com.clele.parts.model.OAuthClient;
import com.clele.parts.repository.OAuthClientRepository;
import com.clele.parts.service.OAuthClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Registration is open to anyone, so what it accepts is a security decision rather than a
 * convenience one. The redirect URI is the part that matters: it is where an authorization code
 * ends up, and the only check standing between a registered client and someone else's code.
 */
@ExtendWith(MockitoExtension.class)
// The refusal cases never reach save(), which strict stubbing would report as an unused stub.
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthClientServiceTest {

    @Mock private OAuthClientRepository clientRepository;

    private OAuthClientService service;

    @BeforeEach
    void setUp() {
        service = new OAuthClientService(clientRepository, new BCryptPasswordEncoder());
        when(clientRepository.save(any(OAuthClient.class)))
                .thenAnswer(invocation -> {
                    OAuthClient saved = invocation.getArgument(0);
                    // Stands in for @PrePersist, which a mocked repository never triggers.
                    saved.setCreatedAt(LocalDateTime.now());
                    return saved;
                });
    }

    private static OAuthRegistrationRequest request(String... redirectUris) {
        OAuthRegistrationRequest request = new OAuthRegistrationRequest();
        request.setClientName("Test Client");
        request.setRedirectUris(List.of(redirectUris));
        return request;
    }

    @Test
    @DisplayName("a client with no auth method is public: an id, PKCE, and no secret to leak")
    void registersAPublicClient() {
        OAuthRegistrationResponse response = service.register(request("http://localhost:9876/callback"));

        assertThat(response.getClientId()).isNotBlank();
        assertThat(response.getClientSecret()).isNull();
        assertThat(response.getTokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(response.getScope()).isEqualTo(OAuthClientService.SCOPE);
        assertThat(response.getGrantTypes()).contains("authorization_code", "refresh_token");
    }

    @Test
    @DisplayName("a client that asks to authenticate gets a secret, returned once")
    void registersAConfidentialClient() {
        OAuthRegistrationRequest request = request("https://app.example.com/callback");
        request.setTokenEndpointAuthMethod("client_secret_post");

        OAuthRegistrationResponse response = service.register(request);

        assertThat(response.getClientSecret()).isNotBlank();
        assertThat(response.getClientSecretExpiresAt()).isZero();
    }

    @Test
    @DisplayName("https anywhere, http only to loopback — a code must not cross a network in clear")
    void acceptsOnlySafeRedirectUris() {
        assertThat(service.register(request("https://claude.ai/api/mcp/auth_callback")).getClientId())
                .isNotBlank();
        assertThat(service.register(request("http://127.0.0.1:1234/cb")).getClientId()).isNotBlank();
        // A desktop app's private scheme: routed by the OS, never over the wire.
        assertThat(service.register(request("myapp://oauth/callback")).getClientId()).isNotBlank();

        assertThatThrownBy(() -> service.register(request("http://parts.example.com/cb")))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> service.register(request("https://example.com/cb#fragment")))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("fragment");
        assertThatThrownBy(() -> service.register(request("/relative/callback")))
                .isInstanceOf(OAuthException.class);
    }

    @Test
    @DisplayName("a registration with no redirect URI is refused — there is nowhere to send a code")
    void refusesRegistrationWithoutARedirectUri() {
        OAuthRegistrationRequest request = new OAuthRegistrationRequest();
        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("redirect_uri");
    }

    @Test
    @DisplayName("only the flows this server implements may be registered for")
    void refusesUnsupportedGrants() {
        OAuthRegistrationRequest request = request("https://app.example.com/cb");
        request.setGrantTypes(List.of("client_credentials"));

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("authorization_code");
    }
}
