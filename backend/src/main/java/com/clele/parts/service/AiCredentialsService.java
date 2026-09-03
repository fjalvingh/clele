package com.clele.parts.service;

import com.clele.parts.config.SecretCipher;
import com.clele.parts.dto.AiConfigDTO;
import com.clele.parts.dto.AiConfigRequest;
import com.clele.parts.dto.AiStatusDTO;
import com.clele.parts.model.AiState;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.OrganisationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Which Anthropic credentials a lookup runs on, and what to say when there are none.
 *
 * <p><b>The key is the organisation's, not the installation's.</b> An AI part search costs 5–13
 * cents, so an app-wide key means one tenant's enthusiasm shows up on everybody's bill with nothing
 * to attribute it to. Each organisation brings its own contract ({@code organisation.ai_api_key},
 * encrypted), and an organisation without one has no AI at all — {@code anthropic.api-key} is
 * deliberately gone. What remains app-wide is the <em>default model</em> and the pricing rates used
 * for the cost line in the log, neither of which spends anything.
 *
 * <p><b>Not configured and out of credit have to be distinguishable.</b> Both end as a lookup that
 * produces nothing, and the fix is completely different — paste a key, versus top up an account
 * somebody else owns. So the reason a call failed is classified from Anthropic's own error and
 * stored on the organisation ({@code ai_status_*}); {@link #status()} hands it to the SPA, which
 * stops offering the AI sources, keeps the free ones, and says which case it is. The next
 * successful call clears it, so a topped-up account recovers on its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AiCredentialsService {

    private final CurrentOrganisationService currentOrganisationService;
    private final OrganisationRepository organisationRepository;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    /**
     * Model used by an organisation that has not chosen one. App-wide because it is not a spending
     * decision — the key is. An organisation that wants a costlier model may set its own, since it
     * is the one paying for it.
     */
    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String defaultModel;

    /** What a call needs: whose key, and which model. Never logged. */
    public record Credentials(String apiKey, String model) {}

    /** The resolved state of one organisation: usable credentials, or the reason there are none. */
    private record Resolution(AiState state, String message, Credentials credentials) {}

    /**
     * Credentials for the organisation in force, or 503 explaining which of the unusable states this
     * is. Callers do not need to check anything first: this is the check.
     */
    public Credentials require() {
        Resolution resolution = resolve(currentOrganisationService.current(), false);
        if (resolution.credentials() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, resolution.message());
        }
        return resolution.credentials();
    }

    /** Whether a lookup is worth attempting for the organisation in force. */
    public boolean isUsable() {
        return resolve(currentOrganisationService.current(), false).state().usable();
    }

    /**
     * Credentials for a deliberate connection test, ignoring a recorded failure — finding out
     * whether that failure still applies is the entire point of a test, and refusing the test
     * because of it would leave a topped-up account with no way back except editing the database.
     */
    public Credentials requireForProbe() {
        Resolution resolution = resolve(currentOrganisationService.current(), true);
        if (resolution.credentials() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, resolution.message());
        }
        return resolution.credentials();
    }

    public AiStatusDTO status() {
        Organisation organisation = currentOrganisationService.current();
        Resolution resolution = resolve(organisation, false);
        return AiStatusDTO.builder()
                .state(resolution.state().name())
                .usable(resolution.state().usable())
                .message(resolution.message())
                .model(effectiveModel(organisation))
                .since(isoOrNull(organisation.getAiStatusAt(), resolution.state()))
                .canConfigure(canConfigure())
                .build();
    }

    public AiConfigDTO config() {
        Organisation organisation = currentOrganisationService.current();
        return toConfig(organisation);
    }

    /**
     * Store this organisation's key and model choice.
     *
     * <p>A blank key leaves the stored one alone (it is never sent to the browser, so the form
     * cannot echo it back); {@code clearApiKey} is the deliberate way to turn AI off. Saving a key
     * clears any recorded failure — the admin has just changed the thing that was failing, so the
     * next lookup gets to find out for itself rather than being pre-emptively refused.
     */
    @Transactional
    public AiConfigDTO update(AiConfigRequest request) {
        Organisation organisation = organisationRepository.findById(currentOrganisationService.currentId())
                .orElseThrow();

        if (request.isClearApiKey()) {
            organisation.setAiApiKey(null);
            organisation.setAiKeyHint(null);
            clearStatus(organisation);
        } else if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            String key = request.getApiKey().trim();
            if (!cipher.available()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "This server cannot store API keys: APP_SECRET_KEY is not set on it. "
                                + "Ask whoever runs the installation to set it and restart.");
            }
            organisation.setAiApiKey(cipher.encrypt(key));
            organisation.setAiKeyHint(hint(key));
            clearStatus(organisation);
        }

        String model = request.getModel() == null ? null : request.getModel().trim();
        organisation.setAiModel(model == null || model.isEmpty() ? null : model);

        return toConfig(organisationRepository.save(organisation));
    }

    // ── Recording what Anthropic said ───────────────────────────────────────────

    /**
     * Turn a failed Anthropic call into the exception to throw, recording the reason on the
     * organisation when it is one that will not fix itself.
     *
     * <p>Every AI call funnels its {@code catch} through here so the classification lives in one
     * place: a rejected key and an exhausted balance both arrive as an ordinary HTTP error, and
     * telling them apart is the difference between "ask your administrator for a new key" and
     * "top up the account".
     */
    public ResponseStatusException translate(Exception e, String what) {
        if (e instanceof HttpStatusCodeException http) {
            String body = http.getResponseBodyAsString();
            AiState classified = classify(http.getStatusCode().value(), body);
            String detail = errorMessage(body);
            if (classified != null) {
                recordFailure(classified, detail);
                return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        describe(classified, detail));
            }
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    what + ": " + (detail == null ? http.getStatusCode().toString() : detail));
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, what + ": " + e.getMessage());
    }

    /**
     * A call worked, so drop any recorded failure. Writes only when there is something to clear —
     * this runs on every successful lookup and must not add a database write to the happy path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void noteSuccess() {
        Organisation current = currentOrganisationService.current();
        if (current.getAiStatusCode() == null) return;
        organisationRepository.findById(current.getId()).ifPresent(organisation -> {
            clearStatus(organisation);
            organisationRepository.save(organisation);
        });
    }

    /**
     * Persist why AI stopped working.
     *
     * <p>{@code REQUIRES_NEW} because the callers are read-only transactions (the datasheet reader
     * is {@code @Transactional(readOnly = true)} at class level) and because the enclosing request
     * is about to fail — the record of *why* must survive that, or the SPA is left showing "not
     * configured" for an organisation whose credits ran out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AiState state, String detail) {
        organisationRepository.findById(currentOrganisationService.currentId())
                .ifPresent(organisation -> {
                    organisation.setAiStatusCode(state.name());
                    organisation.setAiStatusMessage(detail);
                    organisation.setAiStatusAt(LocalDateTime.now());
                    organisationRepository.save(organisation);
                    log.warn("ai-unavailable organisation={} state={} detail={}",
                            organisation.getName(), state, detail);
                });
    }

    /**
     * Which unusable state an HTTP error from Anthropic means, or null when it says nothing lasting
     * about the credentials (a rate limit, an overloaded model, a 500 — all of which come right on
     * their own and must not turn the feature off).
     */
    static AiState classify(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase();
        if (status == 401 || status == 403) return AiState.KEY_REJECTED;
        if (lower.contains("invalid x-api-key") || lower.contains("authentication_error")) {
            return AiState.KEY_REJECTED;
        }
        // "Your credit balance is too low to access the Anthropic API" — a 400 invalid_request_error,
        // indistinguishable from a malformed request except by its text.
        if (lower.contains("credit balance") || lower.contains("insufficient credit")
                || lower.contains("billing")) {
            return AiState.NO_CREDITS;
        }
        return null;
    }

    // ── Resolution ──────────────────────────────────────────────────────────────

    private Resolution resolve(Organisation organisation, boolean ignoreRecordedFailure) {
        if (organisation.getAiApiKey() == null || organisation.getAiApiKey().isBlank()) {
            return unusable(AiState.NOT_CONFIGURED, null);
        }
        if (!cipher.available()) {
            return unusable(AiState.SERVER_SECRET_MISSING, null);
        }
        String key;
        try {
            key = cipher.decrypt(organisation.getAiApiKey());
        } catch (SecretCipher.SecretUnreadableException e) {
            return unusable(AiState.KEY_UNREADABLE, null);
        }
        // A failure recorded by an earlier call outranks "a key is present": the key is present and
        // known not to work, which is exactly the case the fallback exists for.
        AiState recorded = ignoreRecordedFailure ? null : recordedState(organisation);
        if (recorded != null) {
            return unusable(recorded, organisation.getAiStatusMessage());
        }
        return new Resolution(AiState.READY, null,
                new Credentials(key, effectiveModel(organisation)));
    }

    private Resolution unusable(AiState state, String detail) {
        return new Resolution(state, describe(state, detail), null);
    }

    /** The stored failure, ignoring anything unrecognised (an enum renamed since it was written). */
    private AiState recordedState(Organisation organisation) {
        String code = organisation.getAiStatusCode();
        if (code == null || code.isBlank()) return null;
        try {
            AiState state = AiState.valueOf(code);
            return state.usable() ? null : state;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One sentence naming what is wrong and who fixes it. The SPA shows this verbatim. */
    private String describe(AiState state, String detail) {
        String fixer = canConfigure()
                ? "Add one under Admin Actions → AI lookup."
                : "Ask an organisation administrator to add one.";
        return switch (state) {
            case READY -> null;
            case NOT_CONFIGURED -> "AI lookup is not set up for this organisation. " + fixer
                    + " Searching your own catalogue, the component cache and the web still works.";
            case SERVER_SECRET_MISSING -> "AI lookup is unavailable: this server has no "
                    + "APP_SECRET_KEY set, so it cannot read the stored API key. "
                    + "Ask whoever runs the installation to set it.";
            case KEY_UNREADABLE -> "AI lookup is unavailable: the stored API key cannot be "
                    + "decrypted (the server's APP_SECRET_KEY changed). The key has to be entered again — "
                    + (canConfigure() ? "do that under Admin Actions → AI lookup."
                                      : "ask an organisation administrator.");
            case KEY_REJECTED -> "Anthropic rejected this organisation's API key"
                    + suffix(detail) + " A new key is needed. "
                    + (canConfigure() ? "Enter it under Admin Actions → AI lookup."
                                      : "Ask an organisation administrator.");
            case NO_CREDITS -> "This organisation's Anthropic account is out of credit"
                    + suffix(detail)
                    + " AI lookups resume once it is topped up; everything else still works.";
        };
    }

    private static String suffix(String detail) {
        return (detail == null || detail.isBlank()) ? "." : ": " + detail.strip()
                + (detail.strip().endsWith(".") ? "" : ".");
    }

    private AiConfigDTO toConfig(Organisation organisation) {
        Resolution resolution = resolve(organisation, false);
        return AiConfigDTO.builder()
                .hasApiKey(organisation.getAiApiKey() != null && !organisation.getAiApiKey().isBlank())
                .keyHint(organisation.getAiKeyHint())
                .model(organisation.getAiModel())
                .defaultModel(defaultModel)
                .state(resolution.state().name())
                .usable(resolution.state().usable())
                .message(resolution.message())
                .since(isoOrNull(organisation.getAiStatusAt(), resolution.state()))
                .serverSecretConfigured(cipher.available())
                .build();
    }

    private String effectiveModel(Organisation organisation) {
        String chosen = organisation.getAiModel();
        return (chosen == null || chosen.isBlank()) ? defaultModel : chosen;
    }

    private static String isoOrNull(LocalDateTime at, AiState state) {
        return (at == null || state.usable()) ? null : at.toString();
    }

    private static void clearStatus(Organisation organisation) {
        organisation.setAiStatusCode(null);
        organisation.setAiStatusMessage(null);
        organisation.setAiStatusAt(null);
    }

    private static String hint(String key) {
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }

    /** The message says "you can fix this" only to somebody who can. */
    private boolean canConfigure() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> Permissions.ORG_ADMIN.equals(a) || Permissions.GLOBAL_ADMIN.equals(a));
    }

    /** Anthropic's own {@code error.message}, or null when the body is not its error shape. */
    private String errorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            return message.isBlank() ? null : message;
        } catch (Exception e) {
            return null;
        }
    }
}
