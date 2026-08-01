package com.clele.parts.controller;

import com.clele.parts.dto.LoginRequest;
import com.clele.parts.dto.UserDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.OrganisationService;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login / logout / current user")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AppUserRepository userRepository;
    private final UserService userService;
    private final CurrentOrganisationService currentOrganisationService;
    private final OrganisationService organisationService;

    @PostMapping("/login")
    @Operation(summary = "Authenticate and start a session")
    public UserDTO login(@Valid @RequestBody LoginRequest request,
                         HttpServletRequest httpRequest,
                         HttpServletResponse httpResponse) {
        String email = request.getEmail().trim().toLowerCase();
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        // Persist the authenticated context to the session so subsequent requests are recognized.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        return currentUserDTO(email);
    }

    @PostMapping("/logout")
    @Operation(summary = "End the current session")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user")
    public UserDTO me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return currentUserDTO(authentication.getName());
    }

    private UserDTO currentUserDTO(String email) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + email));
        // Resolving the current organisation also seeds it into the session on first call, so the
        // rest of the app has a tenant from the moment the user is known.
        return userService.toCurrentUserDTO(user,
                currentOrganisationService.current(),
                currentOrganisationService.selectable().stream()
                        .map(organisationService::toDTO)
                        .toList());
    }
}
