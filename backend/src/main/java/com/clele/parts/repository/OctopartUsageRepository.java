package com.clele.parts.repository;

import com.clele.parts.model.OctopartUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OctopartUsageRepository extends JpaRepository<OctopartUsage, Long> {

    Optional<OctopartUsage> findByUserIdAndPeriod(Long userId, String period);
}
