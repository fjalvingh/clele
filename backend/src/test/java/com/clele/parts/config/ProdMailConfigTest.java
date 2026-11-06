package com.clele.parts.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server configures mail through {@code MAIL_*} in {@code clele.env}, names Spring does not bind
 * by itself. Losing the mapping raises no error: the app just treats mail as unconfigured and stops
 * sending, so it is pinned here.
 */
@SpringJUnitConfig(classes = ProdMailConfigTest.Config.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "MAIL_PROVIDER=mailersend",
        "MAIL_HOST=smtp.example.com",
        "MAIL_PORT=2525",
        "MAIL_USERNAME=user",
        "MAIL_PASSWORD=secret",
        "MAIL_AUTH=false",
        "MAIL_STARTTLS=false",
        "MAIL_FROM=from@example.com",
        "MAIL_FROM_NAME=Sender",
        "MAILERSEND_API_KEY=key"
})
class ProdMailConfigTest {

    @Configuration
    @EnableConfigurationProperties({AppProperties.class, MailProperties.class})
    static class Config {
    }

    @Autowired
    private MailProperties smtp;

    @Autowired
    private AppProperties app;

    @Test
    void theServersMailVariablesReachTheSmtpSettings() {
        assertThat(smtp.getHost()).isEqualTo("smtp.example.com");
        assertThat(smtp.getPort()).isEqualTo(2525);
        assertThat(smtp.getUsername()).isEqualTo("user");
        assertThat(smtp.getPassword()).isEqualTo("secret");
        assertThat(smtp.getProperties())
                .containsEntry("mail.smtp.auth", "false")
                .containsEntry("mail.smtp.starttls.enable", "false");
    }

    @Test
    void theServersMailVariablesReachTheAppSettings() {
        assertThat(app.getMail().getProvider()).isEqualTo("mailersend");
        assertThat(app.getMail().getFrom()).isEqualTo("from@example.com");
        assertThat(app.getMail().getFromName()).isEqualTo("Sender");
        assertThat(app.getMail().getMailersend().getApiKey()).isEqualTo("key");
    }
}
