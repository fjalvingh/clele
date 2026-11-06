package com.clele.parts.service;

import com.clele.parts.config.AppProperties;
import com.clele.parts.dto.OrganisationStockSummary;
import com.clele.parts.mail.EmailMessage;
import com.clele.parts.mail.MailProvider;
import com.clele.parts.mail.MailProviderRegistry;
import com.clele.parts.mail.MailSendResult;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Organisation;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import com.clele.parts.repository.StockThresholdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The weekly stock summary. What is worth pinning is who gets a mail and when: this runs unattended
 * against real mailboxes, so a wrong "due" either spams everybody or quietly stops the traffic that
 * keeps the mail account alive.
 */
class StockSummaryMailServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0, 0);

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PartRepository parts = mock(PartRepository.class);
    private final StockEntryRepository stockEntries = mock(StockEntryRepository.class);
    private final StockMovementRepository stockMovements = mock(StockMovementRepository.class);
    private final StockThresholdRepository stockThresholds = mock(StockThresholdRepository.class);
    private final MailService mail = mock(MailService.class);
    private final AppProperties properties = new AppProperties();

    private final StockSummaryMailService service = new StockSummaryMailService(users, parts,
            stockEntries, stockMovements, stockThresholds, mail, properties);

    private final Organisation acme = Organisation.builder().id(1L).name("Acme").build();
    private final Organisation template =
            Organisation.builder().id(2L).name("Template").template(true).build();

    @BeforeEach
    void mailWorks() {
        when(mail.isEnabled()).thenReturn(true);
        when(mail.sendStockSummary(any(), anyList())).thenReturn(MailSendResult.succeeded());
        when(stockEntries.totalStockValue(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    private static AppUser user(long id, String email, LocalDateTime lastSent, Organisation... orgs) {
        return AppUser.builder().id(id).email(email).lastStockSummaryAt(lastSent)
                .organisations(Set.of(orgs)).build();
    }

    private void accounts(AppUser... accounts) {
        when(users.findAllByOrderByEmail()).thenReturn(List.of(accounts));
    }

    @Test
    void aUserNeverMailedIsDueAndIsStamped() {
        AppUser user = user(1, "a@example.com", null, acme);
        accounts(user);

        service.sendDueSummaries(NOW);

        verify(mail).sendStockSummary(eq(user), anyList());
        verify(users).markStockSummarySent(eq(1L), any());
    }

    @Test
    void aMailStampedJustAfterLastWeeksCheckIsDueAtThisWeeksCheck() {
        // Sent moments after the 10:00 check a week ago. A plain "7 days" would wait until 11:00,
        // and drift another hour every week.
        accounts(user(1, "a@example.com", NOW.minusDays(7).plusSeconds(5), acme));

        service.sendDueSummaries(NOW);

        verify(mail, times(1)).sendStockSummary(any(), anyList());
    }

    @Test
    void aMailSentLessThanAWeekAgoIsNotRepeated() {
        accounts(user(1, "a@example.com", NOW.minusDays(6), acme));

        service.sendDueSummaries(NOW);

        verify(mail, never()).sendStockSummary(any(), anyList());
    }

    @Test
    void aFailedSendIsNotStampedAndDoesNotStopTheOthers() {
        AppUser refused = user(1, "refused@example.com", null, acme);
        AppUser fine = user(2, "fine@example.com", null, acme);
        accounts(refused, fine);
        when(mail.sendStockSummary(eq(refused), anyList()))
                .thenReturn(MailSendResult.failed("550 no such user"));

        service.sendDueSummaries(NOW);

        verify(users, never()).markStockSummarySent(eq(1L), any());
        verify(users).markStockSummarySent(eq(2L), any());
    }

    @Test
    void withoutAMailProviderNobodyIsMailedOrStamped() {
        when(mail.isEnabled()).thenReturn(false);
        accounts(user(1, "a@example.com", null, acme));

        service.sendDueSummaries(NOW);

        verify(mail, never()).sendStockSummary(any(), anyList());
        verify(users, never()).markStockSummarySent(any(), any());
    }

    @Test
    void theSwitchTurnsItOff() {
        properties.getMail().setStockSummaryEnabled(false);
        accounts(user(1, "a@example.com", null, acme));

        service.sendDueSummaries(NOW);

        verify(mail, never()).sendStockSummary(any(), anyList());
    }

    @Test
    void usersWithoutARealOrganisationOrDeliverableAddressAreNotActive() {
        accounts(user(1, "none@example.com", null),
                user(2, "template@example.com", null, template),
                user(3, "admin@clele.local", null, acme));

        service.sendDueSummaries(NOW);

        verify(mail, never()).sendStockSummary(any(), anyList());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theSummaryCoversRealOrganisationsOnly() {
        AppUser user = user(1, "a@example.com", null, acme, template);
        accounts(user);
        when(parts.countByOrganisationId(1L)).thenReturn(12L);
        when(stockMovements.countChangesSince(1L, NOW.minusDays(7))).thenReturn(3L);

        service.sendDueSummaries(NOW);

        ArgumentCaptor<List<OrganisationStockSummary>> sections = ArgumentCaptor.forClass(List.class);
        verify(mail).sendStockSummary(eq(user), sections.capture());
        assertThat(sections.getValue()).hasSize(1);
        OrganisationStockSummary section = sections.getValue().get(0);
        assertThat(section.organisationName()).isEqualTo("Acme");
        assertThat(section.catalogueParts()).isEqualTo(12);
        assertThat(section.recentChanges()).isEqualTo(3);
        verify(parts, never()).countByOrganisationId(2L);
    }

    @Test
    void theMailSaysTheFiguresUnderThePublicName() throws Exception {
        MailProvider provider = mock(MailProvider.class);
        MailProviderRegistry registry = mock(MailProviderRegistry.class);
        when(registry.active()).thenReturn(Optional.of(provider));
        properties.setBaseUrl("https://parts.example.com");
        AppUser user = AppUser.builder().email("a@example.com").fullName("Sam").build();

        MailSendResult result = new MailService(registry, properties).sendStockSummary(user, List.of(
                new OrganisationStockSummary("Acme", 12, 8, 150, new BigDecimal("12.345"), 2, 3)));

        assertThat(result.sent()).isTrue();
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(provider).send(sent.capture());
        EmailMessage message = sent.getValue();
        assertThat(message.to()).isEqualTo("a@example.com");
        assertThat(message.subject()).contains("Sortiment").doesNotContainIgnoringCase("clele");
        assertThat(message.text())
                .contains("Hello Sam,", "Acme", "Items on hand: 150", "Stock value: € 12.35",
                        "Below minimum stock: 2", "https://parts.example.com")
                .doesNotContainIgnoringCase("clele");
    }
}
