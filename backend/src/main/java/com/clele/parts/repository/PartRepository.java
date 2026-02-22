package com.clele.parts.repository;

import com.clele.parts.model.Part;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long> {

    Optional<Part> findByPartNumber(String partNumber);

    @Query("SELECT p FROM Part p LEFT JOIN FETCH p.category ORDER BY p.name")
    List<Part> findAllWithCategory();

    boolean existsByPartNumber(String partNumber);

    boolean existsByPartNumberAndIdNot(String partNumber, Long id);

    @Query("SELECT COUNT(p) FROM Part p")
    long countAll();
}
