package com.clele.parts.repository;

import com.clele.parts.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByParentIsNull();

    List<Category> findByParentId(Long parentId);

    boolean existsByParentId(Long parentId);

    @Query("SELECT COUNT(p) FROM Part p WHERE p.category.id = :categoryId")
    long countPartsByCategoryId(Long categoryId);
}
