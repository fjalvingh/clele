package com.clele.parts.service;

import com.clele.parts.dto.OctopartResultDTO;
import com.clele.parts.dto.OctopartUsageDTO;
import com.clele.parts.model.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Orchestrates per-user OctoPart (Nexar) search. Each user runs on their own free contract (limited
 * to ~100 requests/month), so credentials and quota are per user. Token fetch is free; the quota is
 * committed (via {@link OctopartQuotaService}) only after a successful token fetch and before the
 * billable search query, so it is recorded even if the search itself later fails. Applying a chosen
 * result is free and lives in {@link PartService#applyOctopart}.
 */
@Service
@RequiredArgsConstructor
public class OctopartService {

    private final NexarApiService nexarApiService;
    private final OctopartQuotaService quotaService;

    public OctopartUsageDTO usage(AppUser user) {
        return quotaService.usage(user, hasCredentials(user));
    }

    public List<OctopartResultDTO> search(AppUser user, String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is required");
        }
        if (!hasCredentials(user)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "OctoPart credentials are not configured for your account. "
                            + "Set them on your profile first.");
        }
        // Token fetch is free — invalid credentials fail here without spending quota.
        String token = nexarApiService.authenticate(
                user.getOctopartClientId(), user.getOctopartClientSecret());
        // Commit one request against the user's quota (429 if exhausted) before the billable query.
        quotaService.consumeOrThrow(user);
        return nexarApiService.search(token, query.trim());
    }

    private boolean hasCredentials(AppUser user) {
        return user.getOctopartClientId() != null && !user.getOctopartClientId().isBlank()
                && user.getOctopartClientSecret() != null && !user.getOctopartClientSecret().isBlank();
    }
}
