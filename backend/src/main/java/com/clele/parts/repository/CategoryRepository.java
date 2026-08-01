package com.clele.parts.repository;

import com.clele.parts.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByOrganisationIdOrderByName(Long organisationId);

    List<Category> findByOrganisationIdAndParentIsNull(Long organisationId);

    List<Category> findByParentId(Long parentId);

    Optional<Category> findByIdAndOrganisationId(Long id, Long organisationId);

    boolean existsByParentId(Long parentId);

    @Query("SELECT COUNT(p) FROM Part p WHERE p.category.id = :categoryId")
    long countPartsByCategoryId(Long categoryId);

    long countByOrganisationId(Long organisationId);
}
