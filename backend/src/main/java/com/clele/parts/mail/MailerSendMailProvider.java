package com.clele.parts.mail;

import com.clele.parts.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <a href="https://developers.mailersend.com/api/v1/email.html">MailerSend</a> — mail over HTTPS
 * instead of SMTP, which is what you want where outbound port 25/587 is blocked (most cloud hosts).
 *
 * <p>Configured with {@code app.mail.mailersend.api-key} (a MailerSend API token). The
 * {@code from} address must belong to a domain verified in the MailerSend account, otherwise the
 * API answers 422 — that is a configuration problem at the provider, not something the app can fix,
 * so the error text is reported through as-is.
 *
 * <p>A successful send returns <b>202 Accepted</b>: MailerSend has queued the mail, not delivered
 * it. There is nothing more to wait for from here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailerSendMailProvider implements MailProvider {

    public static final String NAME = "mailersend";

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        String key = appProperties.getMail().getMailersend().getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public void send(EmailMessage message) {
        AppProperties.Mailersend config = appProperties.getMail().getMailersend();

        Map<String, Object> from = new HashMap<>();
        from.put("email", message.from());
        if (message.fromName() != null && !message.fromName().isBlank()) {
            from.put("name", message.fromName());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("from", from);
        body.put("to", List.of(Map.of("email", message.to())));
        body.put("subject", message.subject());
        body.put("text", message.text());
        if (message.html() != null && !message.html().isBlank()) {
            body.put("html", message.html());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(new ArrayList<>(List.of(MediaType.APPLICATION_JSON)));
        headers.setBearerAuth(config.getApiKey());

        String url = config.getBaseUrl() + "/email";
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            // 202 is the documented success; anything else 2xx we accept but note.
            if (response.getStatusCode().value() != 202) {
                log.info("MailerSend answered {} for mail to {}", response.getStatusCode(),
                        message.to());
            }
        } catch (RestClientResponseException e) {
            // MailerSend explains rejections (unverified domain, suppressed recipient, quota) in
            // the body — that text is the only useful part of the failure, so keep it.
            throw new MailSendException("MailerSend rejected the mail (HTTP "
                    + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new MailSendException("MailerSend send failed: " + e.getMessage(), e);
        }
    }
}
