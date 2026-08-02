package com.clele.parts.repository;

import com.clele.parts.model.InvitationStatus;
import com.clele.parts.model.OrganisationInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganisationInvitationRepository extends JpaRepository<OrganisationInvitation, Long> {

    Optional<OrganisationInvitation> findByToken(String token);

    /** Everything ever sent for one organisation, newest first — the Users screen's history. */
    List<OrganisationInvitation> findByOrganisationIdOrderByCreatedAtDesc(Long organisationId);

    /** Used to refuse a second open invitation for the same address in the same organisation. */
    Optional<OrganisationInvitation> findByEmailAndOrganisationIdAndStatus(
            String email, Long organisationId, InvitationStatus status);
}
