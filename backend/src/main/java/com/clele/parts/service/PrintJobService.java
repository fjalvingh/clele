package com.clele.parts.service;

import com.clele.parts.dto.*;
import com.clele.parts.model.*;
import com.clele.parts.repository.PrintDaemonRepository;
import com.clele.parts.repository.PrintJobRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Delivers print jobs to daemons via long-poll: {@link #enqueue} persists the job and completes
 * any {@link CompletableFuture} a daemon is currently blocked on in {@link #nextForDaemon}, so a
 * waiting daemon gets the job immediately; one that isn't currently polling picks it up on its
 * next poll instead (the future is only a wake-up signal, the row in {@code print_job} is the
 * source of truth). {@code nextForDaemon} itself is deliberately not wrapped in a single
 * transaction — it can block for the whole {@code waitSeconds} window and must not hold a DB
 * connection open while idle; each repository call below is transactional on its own.
 */
@Service
@RequiredArgsConstructor
public class PrintJobService {

    private final PrintJobRepository printJobRepository;
    private final PrintDaemonRepository printDaemonRepository;
    private final CurrentUserService currentUserService;

    private final ConcurrentHashMap<Long, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    @Transactional
    public PrintJobDTO enqueue(PrintJobRequest request) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = printDaemonRepository.findById(request.getDaemonId())
                .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
        if (daemon.getOwner() == null || !daemon.getOwner().getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your daemon");
        }
        byte[] png = Base64.getDecoder().decode(request.getLabelPngBase64());
        PrintJob job = PrintJob.builder()
                .daemon(daemon)
                .requestedBy(me)
                .labelPng(png)
                .status(JobStatus.QUEUED)
                .build();
        job = printJobRepository.save(job);
        wake(daemon.getId());
        return toDto(job);
    }

    @Transactional(readOnly = true)
    public PrintJobDTO getStatus(Long jobId) {
        return toDto(printJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found")));
    }

    /**
     * Blocks up to {@code waitSeconds} for a queued job for this daemon; empty if none arrives.
     * The returned job is immediately flipped to SENT so it isn't handed out again on the next poll.
     */
    public Optional<DaemonJobDTO> nextForDaemon(Long daemonId, int waitSeconds) {
        Optional<DaemonJobDTO> claimed = claimNextQueued(daemonId);
        if (claimed.isPresent()) {
            return claimed;
        }
        CompletableFuture<Void> future = waiters.computeIfAbsent(daemonId, k -> new CompletableFuture<>());
        try {
            future.get(waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            waiters.remove(daemonId, future);
        }
        return claimNextQueued(daemonId);
    }

    @Transactional
    public void complete(Long jobId, DaemonJobCompleteRequest request) {
        PrintJob job = printJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Job not found"));
        job.setStatus("FAILED".equalsIgnoreCase(request.getStatus()) ? JobStatus.FAILED : JobStatus.DONE);
        job.setErrorMessage(request.getError());
        job.setCompletedAt(LocalDateTime.now());
        printJobRepository.save(job);
    }

    // Not @Transactional: called from nextForDaemon within the same bean (self-invocation would
    // bypass the proxy anyway). findFirstBy...() and save() are each transactional on their own
    // via Spring Data JPA's default repository behavior, which is all the atomicity this needs.
    private Optional<DaemonJobDTO> claimNextQueued(Long daemonId) {
        return printJobRepository.findFirstByDaemonIdAndStatusOrderByCreatedAt(daemonId, JobStatus.QUEUED)
                .map(job -> {
                    job.setStatus(JobStatus.SENT);
                    return toDaemonJobDto(printJobRepository.save(job));
                });
    }

    private void wake(Long daemonId) {
        CompletableFuture<Void> future = waiters.get(daemonId);
        if (future != null) {
            future.complete(null);
        }
    }

    private DaemonJobDTO toDaemonJobDto(PrintJob job) {
        return DaemonJobDTO.builder()
                .jobId(job.getId())
                .labelPngBase64(Base64.getEncoder().encodeToString(job.getLabelPng()))
                .printerIp(job.getDaemon().getPrinterIp())
                .tapeWidthMm(job.getDaemon().getTapeWidthMm())
                .build();
    }

    private PrintJobDTO toDto(PrintJob job) {
        return PrintJobDTO.builder()
                .id(job.getId())
                .status(job.getStatus().name())
                .errorMessage(job.getErrorMessage())
                .build();
    }
}
