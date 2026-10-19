package com.clele.parts.repository;

import com.clele.parts.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOrganisationIdAndOwnerIdAndDeletedFalseOrderByUpdatedAtDesc(
            Long organisationId, Long ownerId);

    Optional<Project> findByIdAndOrganisationIdAndOwnerIdAndDeletedFalse(
            Long id, Long organisationId, Long ownerId);

    /** Any live project of the organisation, whoever owns it — the order list is shared. */
    Optional<Project> findByIdAndOrganisationIdAndDeletedFalse(Long id, Long organisationId);

    long countByOrganisationIdAndDeletedFalse(Long organisationId);
}
