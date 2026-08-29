package com.clele.parts.repository;

import com.clele.parts.model.OAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, Long> {

    Optional<OAuthToken> findByAccessTokenHash(String accessTokenHash);

    Optional<OAuthToken> findByRefreshTokenHash(String refreshTokenHash);

    List<OAuthToken> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** Everything still live that one client holds for one user — the blast radius of a replay. */
    List<OAuthToken> findByClientClientIdAndUserIdAndRevokedAtIsNull(String clientId, Long userId);

    List<OAuthToken> findByAuthorizationIdAndRevokedAtIsNull(Long authorizationId);
}
