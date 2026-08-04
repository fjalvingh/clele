package com.clele.parts.repository;

import com.clele.parts.model.SpecAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecAliasRepository extends JpaRepository<SpecAlias, Long> {

    List<SpecAlias> findByOrganisationIdOrderByJsonNameAsc(Long organisationId);

    List<SpecAlias> findBySpecDefinitionIdOrderByJsonNameAsc(Long specDefinitionId);

    Optional<SpecAlias> findByOrganisationIdAndJsonName(Long organisationId, String jsonName);

    void deleteBySpecDefinitionId(Long specDefinitionId);
}
