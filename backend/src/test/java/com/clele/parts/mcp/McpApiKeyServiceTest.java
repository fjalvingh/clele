package com.clele.parts.mcp;

import com.clele.parts.model.AppUser;
import com.clele.parts.model.McpApiKey;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.OrganisationPermission;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.McpApiKeyRepository;
import com.clele.parts.repository.OrganisationRepository;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.CurrentUserService;
import com.clele.parts.service.McpApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifying an MCP token. This is the whole access-control surface of the MCP endpoint, so the
 * cases that must fail matter more here than the one that succeeds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpApiKeyServiceTest {

    private static final Long ORG_ID = 7L;
    private static final Long OTHER_ORG_ID = 8L;
    private static final String SECRET = "s3cret-value";

    @Mock private McpApiKeyRepository keyRepository;
    @Mock private OrganisationRepository organisationRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private CurrentOrganisationService currentOrganisationService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private McpApiKeyService service;
    private AppUser owner;

    @BeforeEach
    void setUp() {
        service = new McpApiKeyService(keyRepository, organisationRepository, passwordEncoder,
                currentUserService, currentOrganisationService);
        owner = member(ORG_ID);
        when(keyRepository.findById(1L)).thenReturn(Optional.of(key(owner, ORG_ID)));
    }

    private static Organisation organisation(Long id) {
        return Organisation.builder().id(id).name("Org " + id).build();
    }

    /** A user who belongs to one organisation and may edit parts there, but not in the other. */
    private static AppUser member(Long organisationId) {
        Set<OrganisationPermission> permissions = new HashSet<>();
        permissions.add(new OrganisationPermission(organisationId, Permissions.PARTS_EDIT));
        permissions.add(new OrganisationPermission(OTHER_ORG_ID, Permissions.ORG_ADMIN));
        AppUser user = AppUser.builder()
                .id(1L)
                .email("owner@example.com")
                .passwordHash("x")
                .organisationPermissions(permissions)
                .build();
        user.getOrganisations().add(organisation(organisationId));
        return user;
    }

    private McpApiKey key(AppUser user, Long organisationId) {
        return McpApiKey.builder()
                .id(1L)
                .user(user)
                .organisation(organisation(organisationId))
                .name("Claude")
                .keyHash(passwordEncoder.encode(SECRET))
                .build();
    }

    private static String token(long id, String secret) {
        return McpApiKeyService.TOKEN_PREFIX + id + "_" + secret;
    }

    @Test
    @DisplayName("a valid token resolves to its owner, its organisation and that organisation's permissions")
    void acceptsAValidToken() {
        Optional<McpApiKeyService.VerifiedKey> verified = service.verify(token(1L, SECRET));

        assertThat(verified).isPresent();
        assertThat(verified.get().email()).isEqualTo("owner@example.com");
        assertThat(verified.get().organisationId()).isEqualTo(ORG_ID);
        // The permission held in the *other* organisation must not travel with this key.
        assertThat(verified.get().authorities()).containsExactly(Permissions.PARTS_EDIT);
    }

    @Test
    @DisplayName("the wrong secret against a real key id is refused")
    void refusesAWrongSecret() {
        assertThat(service.verify(token(1L, "not-the-secret"))).isEmpty();
    }

    @Test
    @DisplayName("an unknown key id is refused")
    void refusesAnUnknownKey() {
        when(keyRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.verify(token(99L, SECRET))).isEmpty();
    }

    @Test
    @DisplayName("a malformed token never reaches the database")
    void refusesMalformedTokens() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("Bearer something")).isEmpty();
        assertThat(service.verify(McpApiKeyService.TOKEN_PREFIX + "nosecret")).isEmpty();
        assertThat(service.verify(McpApiKeyService.TOKEN_PREFIX + "abc_" + SECRET)).isEmpty();
        assertThat(service.verify(McpApiKeyService.TOKEN_PREFIX + "1_")).isEmpty();
        verify(keyRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("a key outlives its owner's membership only as long as the membership does")
    void refusesWhenMembershipIsGone() {
        AppUser evicted = member(ORG_ID);
        evicted.getOrganisations().clear();
        when(keyRepository.findById(1L)).thenReturn(Optional.of(key(evicted, ORG_ID)));

        assertThat(service.verify(token(1L, SECRET))).isEmpty();
    }

    @Test
    @DisplayName("a global administrator needs no explicit membership")
    void acceptsAGlobalAdministrator() {
        AppUser admin = member(ORG_ID);
        admin.getOrganisations().clear();
        admin.getPermissions().add(Permissions.GLOBAL_ADMIN);
        when(keyRepository.findById(1L)).thenReturn(Optional.of(key(admin, ORG_ID)));

        Optional<McpApiKeyService.VerifiedKey> verified = service.verify(token(1L, SECRET));

        assertThat(verified).isPresent();
        assertThat(verified.get().authorities())
                .contains(Permissions.GLOBAL_ADMIN, Permissions.PARTS_EDIT, Permissions.ORG_ADMIN);
    }
}
