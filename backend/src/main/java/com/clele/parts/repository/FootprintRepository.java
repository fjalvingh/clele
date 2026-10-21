package com.clele.parts.repository;

import com.clele.parts.model.Footprint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FootprintRepository extends JpaRepository<Footprint, Long> {

    @EntityGraph(attributePaths = "aliases")
    List<Footprint> findByOrganisationIdOrderByNameAsc(Long organisationId);

    Optional<Footprint> findByIdAndOrganisationId(Long id, Long organisationId);
}
