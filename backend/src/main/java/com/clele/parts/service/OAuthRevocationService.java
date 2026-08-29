package com.clele.parts.service;

import com.clele.parts.model.OAuthToken;
import com.clele.parts.repository.OAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Revoking tokens when a credential is presented in a way that means it has been copied.
 *
 * <p><b>This exists solely to get its own transaction</b>, and that is not incidental. Both callers
 * revoke and then reject, and a rejection is an exception — which rolls back the transaction it was
 * thrown in, taking the revocation with it. The token would be refused this once and keep working
 * afterwards, which is precisely backwards: the request that revealed the compromise is the one
 * request we can be sure about. {@code REQUIRES_NEW} commits the revocation before the caller
 * throws, and a separate bean is what makes the proxy apply it at all (a call to {@code this} would
 * not go through it).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthRevocationService {

    private final OAuthTokenRepository tokenRepository;

    /** Everything one authorization code produced — for a code presented twice. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeIssuedBy(Long authorizationId) {
        return revoke(tokenRepository.findByAuthorizationIdAndRevokedAtIsNull(authorizationId));
    }

    /** Everything one client still holds for one user — for a refresh token used twice. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(String clientId, Long userId) {
        return revoke(tokenRepository.findByClientClientIdAndUserIdAndRevokedAtIsNull(clientId, userId));
    }

    private int revoke(List<OAuthToken> tokens) {
        LocalDateTime now = LocalDateTime.now();
        tokens.forEach(token -> token.setRevokedAt(now));
        tokenRepository.saveAll(tokens);
        return tokens.size();
    }
}
