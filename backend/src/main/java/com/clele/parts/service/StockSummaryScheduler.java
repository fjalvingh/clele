package com.clele.parts.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Triggers the weekly stock summary. Checks at the top of every hour rather than firing once a week:
 * which users are due is stored per user, so a restart neither repeats a mail nor skips one, and a
 * failed send is retried within the hour.
 *
 * <p>Web application only. The command-line profiles ({@code import}, {@code datasheets},
 * {@code specs}) must not mail anybody, and a scheduler thread would keep those runs from exiting.
 */
@Component
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class StockSummaryScheduler {

    private final StockSummaryMailService stockSummaryMailService;

    // Must match StockSummaryMailService.CHECK_INTERVAL.
    @Scheduled(cron = "0 0 * * * *")
    public void check() {
        stockSummaryMailService.sendDueSummaries(LocalDateTime.now());
    }
}
