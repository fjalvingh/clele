package com.clele.parts.repository;

import com.clele.parts.model.PartImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartImageRepository extends JpaRepository<PartImage, Long> {

    List<PartImage> findByPartIdOrderByDisplayOrder(Long partId);

    int countByPartId(Long partId);

    Optional<PartImage> findByIdAndPartId(Long id, Long partId);
}
