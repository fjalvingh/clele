package com.clele.parts.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Plain SMTP through Spring's {@link JavaMailSender}, configured under {@code spring.mail.*}.
 * The default provider — it needs no account anywhere, only a server.
 */
@Component
@RequiredArgsConstructor
public class SmtpMailProvider implements MailProvider {

    public static final String NAME = "smtp";

    private final JavaMailSender mailSender;

    /** Blank unless an SMTP host is configured; that is what makes this provider unconfigured. */
    @Value("${spring.mail.host:}")
    private String mailHost;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return mailHost != null && !mailHost.isBlank();
    }

    @Override
    public void send(EmailMessage message) {
        try {
            if (message.html() == null || message.html().isBlank()) {
                SimpleMailMessage simple = new SimpleMailMessage();
                simple.setFrom(message.from());
                simple.setTo(message.to());
                simple.setSubject(message.subject());
                simple.setText(message.text());
                mailSender.send(simple);
                return;
            }
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            if (message.fromName() != null && !message.fromName().isBlank()) {
                helper.setFrom(message.from(), message.fromName());
            } else {
                helper.setFrom(message.from());
            }
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            mailSender.send(mime);
        } catch (Exception e) {
            throw new MailSendException("SMTP send failed: " + e.getMessage(), e);
        }
    }
}
