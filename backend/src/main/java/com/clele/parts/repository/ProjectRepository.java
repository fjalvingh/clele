package com.clele.parts.repository;

import com.clele.parts.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOrganisationIdAndOwnerIdOrderByUpdatedAtDesc(Long organisationId, Long ownerId);

    Optional<Project> findByIdAndOrganisationIdAndOwnerId(Long id, Long organisationId, Long ownerId);

    long countByOrganisationId(Long organisationId);
}
