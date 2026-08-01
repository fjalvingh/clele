package com.clele.parts.repository;

import com.clele.parts.model.Organisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    List<Organisation> findAllByOrderByName();

    /** The blueprint organisation new organisations are cloned from. */
    Optional<Organisation> findByTemplateTrue();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
