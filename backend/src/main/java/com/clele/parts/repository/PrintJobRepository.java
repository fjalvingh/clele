package com.clele.parts.repository;

import com.clele.parts.model.JobStatus;
import com.clele.parts.model.PrintJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    Optional<PrintJob> findFirstByDaemonIdAndStatusOrderByCreatedAt(Long daemonId, JobStatus status);
}
