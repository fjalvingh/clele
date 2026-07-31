package com.clele.parts.config;

import com.clele.parts.model.PrintDaemon;
import com.clele.parts.repository.PrintDaemonRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code /api/daemon/**} requests via the {@code X-Daemon-Id}/{@code X-Daemon-Key}
 * headers instead of the session cookie. Stateless: on success it sets a request-scoped
 * {@link org.springframework.security.core.Authentication} whose principal is the {@link PrintDaemon}
 * id, no {@code SecurityContextRepository} involved. Leaves the context empty (and the request to
 * be rejected by the matcher's {@code .authenticated()} rule) when the header is missing/invalid,
 * so {@code /api/daemon/register} (permitAll, no key yet) is unaffected.
 */
@RequiredArgsConstructor
public class DaemonApiKeyAuthFilter extends OncePerRequestFilter {

    private final PrintDaemonRepository printDaemonRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String daemonIdHeader = request.getHeader("X-Daemon-Id");
        String apiKey = request.getHeader("X-Daemon-Key");
        if (daemonIdHeader != null && apiKey != null) {
            try {
                Long daemonId = Long.valueOf(daemonIdHeader);
                Optional<PrintDaemon> daemon = printDaemonRepository.findById(daemonId);
                if (daemon.isPresent() && passwordEncoder.matches(apiKey, daemon.get().getApiKeyHash())) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            daemon.get().getId(), null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (NumberFormatException ignored) {
                // Falls through with no authentication set; the matcher rejects it.
            }
        }
        filterChain.doFilter(request, response);
    }
}
