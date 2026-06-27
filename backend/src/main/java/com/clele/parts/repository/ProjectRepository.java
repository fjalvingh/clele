package com.clele.parts.repository;

import com.clele.parts.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    Optional<Project> findByIdAndOwnerId(Long id, Long ownerId);
}
