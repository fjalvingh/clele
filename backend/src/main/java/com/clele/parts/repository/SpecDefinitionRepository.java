package com.clele.parts.repository;

import com.clele.parts.model.SpecDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecDefinitionRepository extends JpaRepository<SpecDefinition, Long> {

    List<SpecDefinition> findByOrganisationIdOrderByDisplayOrderAscNameAsc(Long organisationId);

    Optional<SpecDefinition> findByIdAndOrganisationId(Long id, Long organisationId);

    Optional<SpecDefinition> findByOrganisationIdAndJsonName(Long organisationId, String jsonName);

    List<SpecDefinition> findByGroupIdOrderByDisplayOrderAscNameAsc(Long groupId);

    long countByGroupId(Long groupId);
}
