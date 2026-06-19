package com.clele.parts.service;

import com.clele.parts.dto.OctopartUsageDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.OctopartUsage;
import com.clele.parts.repository.OctopartUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;

/**
 * Per-user, per-month OctoPart (Nexar) request quota. {@link #consumeOrThrow} runs in its own
 * committed transaction so a spent request is recorded even if the subsequent (billable) search
 * call later fails — Nexar charges per request issued regardless of outcome.
 */
@Service
@RequiredArgsConstructor
public class OctopartQuotaService {

    private final OctopartUsageRepository usageRepository;

    @Value("${octopart.monthly-limit:100}")
    private int monthlyLimit;

    public int monthlyLimit() {
        return monthlyLimit;
    }

    @Transactional(readOnly = true)
    public OctopartUsageDTO usage(AppUser user, boolean hasCredentials) {
        int used = usageRepository.findByUserIdAndPeriod(user.getId(), currentPeriod())
                .map(OctopartUsage::getRequestCount)
                .orElse(0);
        return OctopartUsageDTO.builder()
                .limit(monthlyLimit)
                .used(used)
                .remaining(Math.max(0, monthlyLimit - used))
                .hasCredentials(hasCredentials)
                .build();
    }

    /** Spend one request from the user's monthly quota, or throw 429 if the cap is reached. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeOrThrow(AppUser user) {
        String period = currentPeriod();
        OctopartUsage usage = usageRepository.findByUserIdAndPeriod(user.getId(), period)
                .orElseGet(() -> OctopartUsage.builder()
                        .userId(user.getId())
                        .period(period)
                        .requestCount(0)
                        .build());
        if (usage.getRequestCount() >= monthlyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Monthly OctoPart request limit (" + monthlyLimit + ") reached.");
        }
        usage.setRequestCount(usage.getRequestCount() + 1);
        usageRepository.save(usage);
    }

    private String currentPeriod() {
        return YearMonth.now().toString(); // 'YYYY-MM'
    }
}
