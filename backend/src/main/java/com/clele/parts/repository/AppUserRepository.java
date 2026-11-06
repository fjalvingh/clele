package com.clele.parts.repository;

import com.clele.parts.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    /** The members of one organisation, for the (organisation-scoped) Users screen. */
    List<AppUser> findByOrganisationsIdOrderByEmail(Long organisationId);

    /** Every account in the installation, for the Global Administrator's All Users screen. */
    List<AppUser> findAllByOrderByEmail();

    /**
     * Records a sent stock summary. A direct update, so that a background mail does not count as an
     * edit of the account: {@code updated_at} stays as it was.
     */
    @Modifying
    @Transactional
    @Query("UPDATE AppUser u SET u.lastStockSummaryAt = :sentAt WHERE u.id = :userId")
    void markStockSummarySent(Long userId, LocalDateTime sentAt);
}
