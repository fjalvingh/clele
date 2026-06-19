package com.clele.parts.service;

import com.clele.parts.dto.OctopartCredentialsRequest;
import com.clele.parts.dto.OctopartCredentialsStatusDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service settings for the current user. Never returns the OctoPart secret. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    private final CurrentUserService currentUserService;
    private final AppUserRepository userRepository;

    public OctopartCredentialsStatusDTO getOctopartCredentials() {
        return toStatus(currentUserService.current());
    }

    @Transactional
    public OctopartCredentialsStatusDTO updateOctopartCredentials(OctopartCredentialsRequest request) {
        AppUser user = currentUserService.current();
        user.setOctopartClientId(trimToNull(request.getClientId()));
        // Blank secret = keep the existing one (mirrors password-update handling in UserService).
        if (request.getClientSecret() != null && !request.getClientSecret().isBlank()) {
            user.setOctopartClientSecret(request.getClientSecret().trim());
        }
        // If the client id was cleared, clear the secret too so the pair stays consistent.
        if (user.getOctopartClientId() == null) {
            user.setOctopartClientSecret(null);
        }
        return toStatus(userRepository.save(user));
    }

    private OctopartCredentialsStatusDTO toStatus(AppUser user) {
        return OctopartCredentialsStatusDTO.builder()
                .hasClientId(user.getOctopartClientId() != null && !user.getOctopartClientId().isBlank())
                .hasClientSecret(user.getOctopartClientSecret() != null && !user.getOctopartClientSecret().isBlank())
                .clientId(user.getOctopartClientId())
                .build();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
