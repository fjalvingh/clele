package com.clele.parts.repository;

import com.clele.parts.model.McpApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpApiKeyRepository extends JpaRepository<McpApiKey, Long> {

    List<McpApiKey> findByUserIdOrderByCreatedAtDesc(Long userId);
}
