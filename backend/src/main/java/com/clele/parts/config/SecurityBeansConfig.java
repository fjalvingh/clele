package com.clele.parts.config;

import com.clele.parts.service.AppUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * The security beans that ordinary components depend on, held apart from {@link SecurityConfig} so
 * they exist in a non-web context too. None of them touch Spring MVC — they are plain objects.
 *
 * <p>{@link SecurityConfig} is {@code @ConditionalOnWebApplication} because {@code @EnableWebSecurity}
 * and its {@code MvcRequestMatcher}-based filter chains need Spring MVC, which is absent when a CLI
 * profile sets {@code web-application-type: none}. Everything here is still required in that
 * context: {@code PasswordEncoder} by {@code InvitationService}, {@code PrintDaemonService} and
 * {@code AdminUserService}, {@code SecurityContextRepository} by {@code PermissionService}, and
 * {@code AuthenticationManager} by {@code AuthController} — which is component-scanned and
 * instantiated even with no web server, since {@code @RestController} is a {@code @Component} and
 * only the MVC *infrastructure* disappears. Leaving any of them in the gated class just moves the
 * startup failure.
 */
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
