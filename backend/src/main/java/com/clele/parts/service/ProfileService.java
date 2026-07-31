package com.clele.parts.service;

import com.clele.parts.dto.OctopartCredentialsRequest;
import com.clele.parts.dto.OctopartCredentialsStatusDTO;
import com.clele.parts.dto.PrintingPreferenceDTO;
import com.clele.parts.dto.PrintingPreferenceRequest;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.PrintDaemon;
import com.clele.parts.model.PrintMethod;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.PrintDaemonRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Self-service settings for the current user. Never returns the OctoPart secret. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    private final CurrentUserService currentUserService;
    private final AppUserRepository userRepository;
    private final PrintDaemonRepository printDaemonRepository;

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

    public PrintingPreferenceDTO getPrintingPreference() {
        return toPrintingPreference(currentUserService.current());
    }

    @Transactional
    public PrintingPreferenceDTO updatePrintingPreference(PrintingPreferenceRequest request) {
        AppUser user = currentUserService.current();
        PrintMethod method;
        try {
            method = PrintMethod.valueOf(request.getPrintMethod());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid print method");
        }
        user.setPrintMethod(method);
        if (request.getPreferredDaemonId() == null) {
            user.setPreferredDaemon(null);
        } else {
            PrintDaemon daemon = printDaemonRepository.findById(request.getPreferredDaemonId())
                    .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
            if (daemon.getOwner() == null || !daemon.getOwner().getId().equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your daemon");
            }
            user.setPreferredDaemon(daemon);
        }
        return toPrintingPreference(userRepository.save(user));
    }

    private PrintingPreferenceDTO toPrintingPreference(AppUser user) {
        return PrintingPreferenceDTO.builder()
                .printMethod(user.getPrintMethod().name())
                .preferredDaemonId(user.getPreferredDaemon() == null ? null : user.getPreferredDaemon().getId())
                .build();
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
