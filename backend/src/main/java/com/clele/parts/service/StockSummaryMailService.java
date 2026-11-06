package com.clele.parts.service;

import com.clele.parts.config.AppProperties;
import com.clele.parts.dto.OrganisationStockSummary;
import com.clele.parts.mail.MailSendResult;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Organisation;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import com.clele.parts.repository.StockThresholdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mails every active user a short summary of their stock, once every 7 days. The regular traffic is
 * also what keeps the mail provider's account alive, see {@code docs/auth.md}.
 *
 * <p>There is no "active" flag on an account, so here it means: a member of at least one real
 * (non-template) organisation, with an address that can receive mail.
 *
 * <p>Runs outside any request, so it names each organisation explicitly rather than going through
 * {@code CurrentOrganisationService}. It holds no transaction across a send, because a slow mail
 * server must not pin a database connection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockSummaryMailService {

    /** How often {@code StockSummaryScheduler} calls {@link #sendDueSummaries}. */
    static final Duration CHECK_INTERVAL = Duration.ofHours(1);

    /**
     * Due one check short of 7 days. A mail is stamped moments <em>after</em> the check that sent
     * it, so a full 7 days would miss that check a week later and slip an hour every week.
     */
    private static final Duration DUE_AFTER = Duration.ofDays(7).minus(CHECK_INTERVAL);

    private final AppUserRepository users;
    private final PartRepository parts;
    private final StockEntryRepository stockEntries;
    private final StockMovementRepository stockMovements;
    private final StockThresholdRepository stockThresholds;
    private final MailService mailService;
    private final AppProperties appProperties;

    /** Send the summary to every active user who is due one. */
    public void sendDueSummaries(LocalDateTime now) {
        if (!appProperties.getMail().isStockSummaryEnabled()) {
            return;
        }
        if (!mailService.isEnabled()) {
            // Nobody is stamped, so everyone is due as soon as mail can go out. Said out loud,
            // because this is exactly how a mail account would quietly lapse.
            log.info("Stock summary: skipped, no mail provider is configured");
            return;
        }
        List<AppUser> active = users.findAllByOrderByEmail().stream()
                .filter(StockSummaryMailService::isActive)
                .toList();
        if (active.isEmpty()) {
            log.warn("Stock summary: no active users to mail, so no mail is going out at all");
            return;
        }

        // Members of one organisation share its figures: work them out once per run.
        Map<Long, OrganisationStockSummary> summaries = new HashMap<>();
        int sent = 0;
        int failed = 0;
        for (AppUser user : active) {
            if (!isDue(user, now)) {
                continue;
            }
            LocalDateTime since = now.minusDays(7);
            List<OrganisationStockSummary> sections = realOrganisations(user).stream()
                    .sorted(Comparator.comparing(Organisation::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(organisation -> summaries.computeIfAbsent(organisation.getId(),
                            id -> summarize(organisation, since)))
                    .toList();
            MailSendResult result = mailService.sendStockSummary(user, sections);
            if (result.sent()) {
                // Only now: a failed send stays due and is tried again at the next check.
                users.markStockSummarySent(user.getId(), LocalDateTime.now());
                sent++;
            } else {
                failed++;
            }
        }
        if (sent > 0 || failed > 0) {
            log.info("Stock summary: {} sent, {} failed", sent, failed);
        }
    }

    private OrganisationStockSummary summarize(Organisation organisation, LocalDateTime since) {
        Long id = organisation.getId();
        return new OrganisationStockSummary(
                organisation.getName(),
                parts.countByOrganisationId(id),
                stockEntries.countStockedParts(id),
                stockEntries.sumUnitsOnHand(id),
                stockEntries.totalStockValue(id),
                stockThresholds.countLowStock(id),
                stockMovements.countChangesSince(id, since));
    }

    private static boolean isDue(AppUser user, LocalDateTime now) {
        LocalDateTime last = user.getLastStockSummaryAt();
        return last == null || !last.plus(DUE_AFTER).isAfter(now);
    }

    private static boolean isActive(AppUser user) {
        return !realOrganisations(user).isEmpty() && canReceiveMail(user.getEmail());
    }

    /**
     * False for {@code .local} addresses such as the seeded {@code admin@clele.local}. They can
     * never be delivered, and bounces count against the account this mail is meant to keep healthy.
     */
    private static boolean canReceiveMail(String email) {
        return !email.toLowerCase(Locale.ROOT).endsWith(".local");
    }

    /** The template organisation is a blueprint and holds no stock worth reporting. */
    private static List<Organisation> realOrganisations(AppUser user) {
        List<Organisation> real = new ArrayList<>();
        for (Organisation organisation : user.getOrganisations()) {
            if (!organisation.isTemplate()) {
                real.add(organisation);
            }
        }
        return real;
    }
}
