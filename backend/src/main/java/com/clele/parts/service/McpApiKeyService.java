package com.clele.parts.service;

import com.clele.parts.dto.McpApiKeyCreatedDTO;
import com.clele.parts.dto.McpApiKeyDTO;
import com.clele.parts.dto.McpApiKeyRequest;
import com.clele.parts.mcp.McpPrincipal;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.McpApiKey;
import com.clele.parts.model.Organisation;
import com.clele.parts.repository.McpApiKeyRepository;
import com.clele.parts.repository.OrganisationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Issues, lists, revokes and verifies the tokens that authenticate the read-only MCP endpoint.
 *
 * <p><b>The token format is {@code clele_mcp_<id>_<secret>}</b>. The id in front is not decoration:
 * only a BCrypt hash of the secret is stored, and a hash cannot be looked up, so the token has to
 * say which row to compare against — the same shape as the print daemon's {@code X-Daemon-Id} +
 * {@code X-Daemon-Key} pair, folded into one header value because MCP clients configure a single
 * header.
 *
 * <p>Verification re-checks the owner's membership every time. A key pins an organisation at
 * creation, but membership can be revoked afterwards, and a stored credential must not outlive the
 * access it was granted under.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class McpApiKeyService {

    /** Marks a token as ours, and tells a leak-scanner what it found. */
    public static final String TOKEN_PREFIX = "clele_mcp_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * How stale {@code last_used_at} may get before a request writes it. Without a floor, every MCP
     * call — a read-only endpoint — would turn into a write.
     */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final McpApiKeyRepository keyRepository;
    private final OrganisationRepository organisationRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;

    /** The current user's own keys, newest first. */
    public List<McpApiKeyDTO> findMine() {
        return keyRepository.findByUserIdOrderByCreatedAtDesc(currentUserService.current().getId())
                .stream()
                .map(McpApiKeyService::toDTO)
                .toList();
    }

    /**
     * Issue a key for the current user, against an organisation they may actually work in. The
     * token is returned here and nowhere else.
     */
    @Transactional
    public McpApiKeyCreatedDTO create(McpApiKeyRequest request) {
        AppUser me = currentUserService.current();
        Long organisationId = request.getOrganisationId() != null
                ? request.getOrganisationId()
                : currentOrganisationService.currentId();
        if (!currentOrganisationService.isSelectable(organisationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not a member of that organisation");
        }
        Organisation organisation = organisationRepository.findById(organisationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Organisation not found"));

        String secret = generateSecret();
        McpApiKey key = keyRepository.save(McpApiKey.builder()
                .user(me)
                .organisation(organisation)
                .name(request.getName().trim())
                .keyHash(passwordEncoder.encode(secret))
                .build());

        log.info("Issued MCP key {} ('{}') for {} in organisation {}",
                key.getId(), key.getName(), me.getEmail(), organisation.getName());
        return McpApiKeyCreatedDTO.builder()
                .key(toDTO(key))
                .token(TOKEN_PREFIX + key.getId() + "_" + secret)
                .build();
    }

    /** Revoke one of the current user's own keys. Someone else's is a 404, not a 403. */
    @Transactional
    public void delete(Long id) {
        Long myId = currentUserService.current().getId();
        McpApiKey key = keyRepository.findById(id)
                .filter(k -> k.getUser().getId().equals(myId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found"));
        keyRepository.delete(key);
        log.info("Revoked MCP key {} ('{}')", key.getId(), key.getName());
    }

    /**
     * Verify a raw token. Empty for anything that does not check out — a malformed token, an
     * unknown or revoked key, a wrong secret, or an owner who has since lost the organisation the
     * key was issued against.
     */
    @Transactional
    public Optional<McpPrincipal> verify(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) return Optional.empty();
        String rest = token.substring(TOKEN_PREFIX.length());
        int separator = rest.indexOf('_');
        if (separator <= 0 || separator == rest.length() - 1) return Optional.empty();

        Long keyId;
        try {
            keyId = Long.valueOf(rest.substring(0, separator));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String secret = rest.substring(separator + 1);

        Optional<McpApiKey> found = keyRepository.findById(keyId);
        if (found.isEmpty() || !passwordEncoder.matches(secret, found.get().getKeyHash())) {
            return Optional.empty();
        }

        McpApiKey key = found.get();
        AppUser user = key.getUser();
        Long organisationId = key.getOrganisation().getId();
        boolean member = user.isGlobalAdmin() || user.getOrganisations().stream()
                .anyMatch(o -> o.getId().equals(organisationId));
        if (!member) {
            log.warn("MCP key {} refused: {} is no longer a member of organisation {}",
                    key.getId(), user.getEmail(), organisationId);
            return Optional.empty();
        }

        touch(key);
        Set<String> authorities = new LinkedHashSet<>(user.getPermissions());
        authorities.addAll(user.permissionsIn(organisationId));
        return Optional.of(new McpPrincipal(user.getEmail(), organisationId, authorities));
    }

    /** Record the use, but not on every single call — see {@link #TOUCH_INTERVAL}. */
    private void touch(McpApiKey key) {
        LocalDateTime now = LocalDateTime.now();
        if (key.getLastUsedAt() == null
                || key.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
            key.setLastUsedAt(now);
            keyRepository.save(key);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static McpApiKeyDTO toDTO(McpApiKey key) {
        return McpApiKeyDTO.builder()
                .id(key.getId())
                .name(key.getName())
                .organisationId(key.getOrganisation().getId())
                .organisationName(key.getOrganisation().getName())
                .createdAt(key.getCreatedAt())
                .lastUsedAt(key.getLastUsedAt())
                .build();
    }
}
