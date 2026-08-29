package com.clele.parts.config;

import com.clele.parts.repository.PrintDaemonRepository;
import com.clele.parts.service.McpApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Map;

/**
 * Session-cookie security for the SPA + REST API.
 *
 * <p>All {@code /api/**} endpoints require an authenticated session except {@code /api/auth/login};
 * specific mutations are further gated with {@code @PreAuthorize} (method security). Static SPA
 * assets and the client-router fallback are public. CSRF is disabled (token-style JSON API with a
 * SameSite cookie); unauthenticated/forbidden API calls return JSON 401/403 so the client can react.
 *
 * <p><b>Servlet-only.</b> {@code @EnableWebSecurity} and the {@code MvcRequestMatcher}-based chains
 * below require Spring MVC, so this class must not load under a CLI profile that sets
 * {@code web-application-type: none} (the {@code import} and {@code datasheets} profiles) — without
 * the guard those runners die at startup on a missing {@code mvcHandlerMappingIntrospector}. The
 * two security beans that ordinary services depend on live in {@link SecurityBeansConfig} so they
 * remain available in every context.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    /**
     * Daemon-facing endpoints: authenticated via the {@code X-Daemon-Id}/{@code X-Daemon-Key}
     * headers ({@link DaemonApiKeyAuthFilter}), not the session cookie. Must come before the
     * session-cookie chain below since {@code securityMatcher} scopes it to just this prefix.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain daemonSecurityFilterChain(HttpSecurity http,
                                                          PrintDaemonRepository printDaemonRepository,
                                                          PasswordEncoder passwordEncoder)
            throws Exception {
        http
                .securityMatcher("/api/daemon/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .addFilterBefore(new DaemonApiKeyAuthFilter(printDaemonRepository, passwordEncoder),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/daemon/register").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeError(res,
                                HttpServletResponse.SC_UNAUTHORIZED, "Invalid daemon credentials")));
        return http.build();
    }

    /**
     * The MCP endpoint: authenticated by an API key ({@link McpApiKeyAuthFilter}), not the session
     * cookie, because the caller is an AI client with no browser. Scoped to exactly {@code /api/mcp}
     * so that key <em>management</em> ({@code /api/profile/mcp-keys}) stays on the session chain
     * below — a key must never be able to mint another one.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                                      McpApiKeyService mcpApiKeyService)
            throws Exception {
        http
                .securityMatcher("/api/mcp")
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .addFilterBefore(new McpApiKeyAuthFilter(mcpApiKeyService),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeError(res,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "A valid MCP API key is required (X-Api-Key or Authorization: Bearer)")));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   OrganisationAuthoritiesFilter organisationAuthoritiesFilter)
            throws Exception {
        http
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(c -> c.securityContextRepository(securityContextRepository))
                // Authorities are derived from the DB per request, for the organisation in force —
                // never trusted from the stored session. Must run after the context is loaded and
                // before authorization decides.
                .addFilterAfter(organisationAuthoritiesFilter, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/settings").permitAll()
                        // Answering an invitation is done from a mailed link by someone who may
                        // have no account at all. The token in the path is the credential; see
                        // InvitationAccessController.
                        .requestMatchers("/api/invitations/token/**").permitAll()
                        .requestMatchers("/api-docs/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Everything else is the SPA shell / static assets — served publicly.
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeError(res,
                                HttpServletResponse.SC_UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((req, res, e) -> writeError(res,
                                HttpServletResponse.SC_FORBIDDEN, "You don't have permission to do that")));
        return http.build();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
