package com.clele.parts.repository;

import com.clele.parts.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
