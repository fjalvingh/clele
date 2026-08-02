package com.clele.parts.mail;

import com.clele.parts.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the {@link MailProvider} named by {@code app.mail.provider} out of every provider on the
 * classpath. Switching providers is exactly this one setting.
 *
 * <p>An unknown name is a startup failure — silently falling back to another provider would mean a
 * typo quietly sends mail through the wrong account, or not at all.
 */
@Component
@Slf4j
public class MailProviderRegistry {

    private final Map<String, MailProvider> providers;
    private final AppProperties appProperties;

    public MailProviderRegistry(List<MailProvider> providers, AppProperties appProperties) {
        this.providers = providers.stream().collect(Collectors.toMap(
                p -> p.name().toLowerCase(), Function.identity()));
        this.appProperties = appProperties;
    }

    @PostConstruct
    void report() {
        String configured = configuredName();
        if (MailProviders.NONE.equals(configured)) {
            log.info("Mail provider: none — mails are logged, not sent.");
            return;
        }
        MailProvider provider = require(configured);
        log.info("Mail provider: {} ({})", provider.name(),
                provider.isConfigured() ? "configured" : "NOT configured — mails will be logged");
    }

    /** The selected provider, or empty when none is selected or it lacks its configuration. */
    public Optional<MailProvider> active() {
        String configured = configuredName();
        if (MailProviders.NONE.equals(configured)) {
            return Optional.empty();
        }
        MailProvider provider = require(configured);
        return provider.isConfigured() ? Optional.of(provider) : Optional.empty();
    }

    /** Every provider this build knows about — useful for diagnostics and error messages. */
    public List<String> available() {
        return providers.keySet().stream().sorted().toList();
    }

    private String configuredName() {
        String name = appProperties.getMail().getProvider();
        return name == null || name.isBlank() ? MailProviders.NONE : name.trim().toLowerCase();
    }

    private MailProvider require(String name) {
        MailProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalStateException("Unknown app.mail.provider '" + name
                    + "'. Known providers: " + String.join(", ", available())
                    + " (or '" + MailProviders.NONE + "' to disable sending).");
        }
        return provider;
    }
}
