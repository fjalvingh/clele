package com.clele.parts.repository;

import com.clele.parts.model.SpecGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecGroupRepository extends JpaRepository<SpecGroup, Long> {

    List<SpecGroup> findByOrganisationIdOrderByDisplayOrderAscNameAsc(Long organisationId);

    Optional<SpecGroup> findByIdAndOrganisationId(Long id, Long organisationId);

    Optional<SpecGroup> findByOrganisationIdAndNameIgnoreCase(Long organisationId, String name);

    boolean existsByOrganisationIdAndNameIgnoreCaseAndIdNot(Long organisationId, String name, Long id);

    boolean existsByOrganisationIdAndNameIgnoreCase(Long organisationId, String name);
}
