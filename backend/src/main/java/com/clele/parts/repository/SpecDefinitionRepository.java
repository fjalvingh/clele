package com.clele.parts.repository;

import com.clele.parts.model.SpecDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpecDefinitionRepository extends JpaRepository<SpecDefinition, Long> {
    List<SpecDefinition> findAllByOrderByDisplayOrderAscNameAsc();
}
