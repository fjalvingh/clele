package com.clele.parts.repository;

import com.clele.parts.model.OAuthAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OAuthAuthorizationRepository extends JpaRepository<OAuthAuthorization, Long> {

    Optional<OAuthAuthorization> findByRequestId(String requestId);

    Optional<OAuthAuthorization> findByCodeHash(String codeHash);

    /** Housekeeping: requests that were never approved, and codes long since exchanged. */
    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
