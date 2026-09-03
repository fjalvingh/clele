package com.clele.parts.service;

import com.clele.parts.config.SecretCipher;
import com.clele.parts.dto.AiConfigDTO;
import com.clele.parts.dto.AiConfigRequest;
import com.clele.parts.dto.AiStatusDTO;
import com.clele.parts.model.AiState;
import com.clele.parts.model.Organisation;
import com.clele.parts.repository.OrganisationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Per-organisation AI credentials.
 *
 * <p>What is worth pinning is the telling-apart. "Nobody configured this" and "the credits ran out"
 * both end as a lookup that returns nothing, and the fixes have nothing in common — so the
 * classification of Anthropic's own error, and the state it leaves the organisation in, are the
 * whole feature. The rest (a rate limit, a 500) must NOT turn the feature off, because it comes
 * right on its own.
 */
class AiCredentialsServiceTest {

    private static final String DEFAULT_MODEL = "claude-haiku-4-5-20251001";

    private final CurrentOrganisationService currentOrganisation = mock(CurrentOrganisationService.class);
    private final OrganisationRepository organisations = mock(OrganisationRepository.class);
    private final SecretCipher cipher = mock(SecretCipher.class);

    private AiCredentialsService service(Organisation organisation) {
        when(currentOrganisation.current()).thenReturn(organisation);
        when(currentOrganisation.currentId()).thenReturn(organisation.getId());
        when(organisations.findById(organisation.getId())).thenReturn(Optional.of(organisation));
        when(organisations.save(any(Organisation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AiCredentialsService service =
                new AiCredentialsService(currentOrganisation, organisations, cipher, new ObjectMapper());
        ReflectionTestUtils.setField(service, "defaultModel", DEFAULT_MODEL);
        return service;
    }

    private static Organisation organisation() {
        return Organisation.builder().id(7L).name("Acme").build();
    }

    private Organisation withKey() {
        Organisation organisation = organisation();
        organisation.setAiApiKey("cipher-text");
        organisation.setAiKeyHint("4Xa2");
        when(cipher.available()).thenReturn(true);
        when(cipher.decrypt("cipher-text")).thenReturn("sk-ant-real-key-4Xa2");
        return organisation;
    }

    // ── Classification ──────────────────────────────────────────────────────────

    @Test
    void aRejectedKeyIsToldApartFromAnExhaustedBalance() {
        assertThat(AiCredentialsService.classify(401,
                "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"))
                .isEqualTo(AiState.KEY_REJECTED);
        assertThat(AiCredentialsService.classify(400,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                        + "\"Your credit balance is too low to access the Anthropic API\"}}"))
                .isEqualTo(AiState.NO_CREDITS);
    }

    /** A rate limit or a server error must not turn the feature off: it fixes itself. */
    @Test
    void aTransientFailureIsNotClassifiedAtAll() {
        assertThat(AiCredentialsService.classify(429,
                "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")).isNull();
        assertThat(AiCredentialsService.classify(529,
                "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}")).isNull();
        assertThat(AiCredentialsService.classify(400,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens too large\"}}"))
                .isNull();
    }

    /**
     * The exhausted balance arrives as an ordinary 400 and has to be recorded on the organisation,
     * or the next screen shows "not configured" for an organisation that is configured perfectly
     * well and simply has to pay.
     */
    @Test
    void anExhaustedBalanceIsRecordedOnTheOrganisation() {
        Organisation organisation = withKey();
        AiCredentialsService service = service(organisation);

        ResponseStatusException thrown = service.translate(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null,
                ("{\"error\":{\"message\":\"Your credit balance is too low to access the "
                        + "Anthropic API\"}}").getBytes(), null), "AI search request failed");

        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(thrown.getReason()).contains("out of credit");
        assertThat(organisation.getAiStatusCode()).isEqualTo(AiState.NO_CREDITS.name());
        assertThat(service.status().isUsable()).isFalse();
    }

    @Test
    void aTransientFailureLeavesTheOrganisationAlone() {
        Organisation organisation = withKey();
        AiCredentialsService service = service(organisation);

        ResponseStatusException thrown = service.translate(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null,
                "{\"error\":{\"message\":\"rate limited\"}}".getBytes(), null), "AI search failed");

        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(organisation.getAiStatusCode()).isNull();
        assertThat(service.status().isUsable()).isTrue();
    }

    // ── Resolution ──────────────────────────────────────────────────────────────

    @Test
    void anOrganisationWithoutAKeyGetsNoAiAndIsToldSo() {
        AiCredentialsService service = service(organisation());

        AiStatusDTO status = service.status();
        assertThat(status.getState()).isEqualTo(AiState.NOT_CONFIGURED.name());
        assertThat(status.isUsable()).isFalse();
        assertThat(status.getMessage()).contains("not set up");
        // The point of the fallback: the message says what still works.
        assertThat(status.getMessage()).contains("component cache");

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, service::require);
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aStoredKeyIsUsedWithTheDefaultModelUntilTheOrganisationChoosesOne() {
        Organisation organisation = withKey();
        AiCredentialsService service = service(organisation);

        assertThat(service.require().apiKey()).isEqualTo("sk-ant-real-key-4Xa2");
        assertThat(service.require().model()).isEqualTo(DEFAULT_MODEL);

        organisation.setAiModel("claude-sonnet-5");
        assertThat(service.require().model()).isEqualTo("claude-sonnet-5");
    }

    /** A secret changed after the key was saved: only re-entering it helps, so say that. */
    @Test
    void aKeyThatNoLongerDecryptsReadsAsSuchRatherThanAsAnApiFailure() {
        Organisation organisation = organisation();
        organisation.setAiApiKey("cipher-text");
        when(cipher.available()).thenReturn(true);
        when(cipher.decrypt("cipher-text"))
                .thenThrow(new SecretCipher.SecretUnreadableException(new RuntimeException("bad tag")));

        AiStatusDTO status = service(organisation).status();
        assertThat(status.getState()).isEqualTo(AiState.KEY_UNREADABLE.name());
        assertThat(status.getMessage()).contains("APP_SECRET_KEY");
    }

    @Test
    void aServerWithNoSecretSaysSoRatherThanBlamingTheKey() {
        Organisation organisation = organisation();
        organisation.setAiApiKey("cipher-text");
        when(cipher.available()).thenReturn(false);

        AiStatusDTO status = service(organisation).status();
        assertThat(status.getState()).isEqualTo(AiState.SERVER_SECRET_MISSING.name());
    }

    /**
     * A recorded failure blocks ordinary lookups — that is the fallback — but must not block the
     * connection test, or a topped-up account would have no way back.
     */
    @Test
    void aRecordedFailureBlocksLookupsButNotTheTest() {
        Organisation organisation = withKey();
        organisation.setAiStatusCode(AiState.NO_CREDITS.name());
        organisation.setAiStatusAt(LocalDateTime.now());
        AiCredentialsService service = service(organisation);

        assertThrows(ResponseStatusException.class, service::require);
        assertThat(service.requireForProbe().apiKey()).isEqualTo("sk-ant-real-key-4Xa2");
    }

    @Test
    void aSuccessfulCallClearsARecordedFailure() {
        Organisation organisation = withKey();
        organisation.setAiStatusCode(AiState.NO_CREDITS.name());
        AiCredentialsService service = service(organisation);

        service.noteSuccess();

        assertThat(organisation.getAiStatusCode()).isNull();
        assertThat(service.status().getState()).isEqualTo(AiState.READY.name());
    }

    // ── Configuration ───────────────────────────────────────────────────────────

    @Test
    void savingAKeyEncryptsItKeepsAHintAndClearsTheRecordedFailure() {
        Organisation organisation = organisation();
        organisation.setAiStatusCode(AiState.KEY_REJECTED.name());
        when(cipher.available()).thenReturn(true);
        when(cipher.encrypt("sk-ant-new-key-9Zb1")).thenReturn("new-cipher-text");
        when(cipher.decrypt("new-cipher-text")).thenReturn("sk-ant-new-key-9Zb1");

        AiConfigDTO config = service(organisation)
                .update(new AiConfigRequest("  sk-ant-new-key-9Zb1  ", false, ""));

        assertThat(organisation.getAiApiKey()).isEqualTo("new-cipher-text");
        assertThat(config.getKeyHint()).isEqualTo("9Zb1");
        assertThat(config.getState()).isEqualTo(AiState.READY.name());
        assertThat(config.getModel()).isNull();
        assertThat(config.getDefaultModel()).isEqualTo(DEFAULT_MODEL);
    }

    /** Blank means "leave it alone": the stored key is never sent to the browser to echo back. */
    @Test
    void aBlankKeyKeepsTheStoredOneAndTheClearFlagRemovesIt() {
        Organisation organisation = withKey();
        AiCredentialsService service = service(organisation);

        service.update(new AiConfigRequest("", false, "claude-sonnet-5"));
        assertThat(organisation.getAiApiKey()).isEqualTo("cipher-text");
        assertThat(organisation.getAiModel()).isEqualTo("claude-sonnet-5");

        AiConfigDTO cleared = service.update(new AiConfigRequest(null, true, "claude-sonnet-5"));
        assertThat(organisation.getAiApiKey()).isNull();
        assertThat(cleared.getState()).isEqualTo(AiState.NOT_CONFIGURED.name());
    }

    /** Accepting a key the server cannot encrypt would leave an admin sure they had fixed it. */
    @Test
    void aServerWithNoSecretRefusesToStoreAKey() {
        when(cipher.available()).thenReturn(false);
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service(organisation()).update(new AiConfigRequest("sk-ant-x", false, null)));
        assertThat(thrown.getReason()).contains("APP_SECRET_KEY");
    }
}
