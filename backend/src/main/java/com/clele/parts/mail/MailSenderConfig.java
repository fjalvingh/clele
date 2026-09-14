package com.clele.parts.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Spring Boot only auto-configures a {@link JavaMailSender} bean when {@code spring.mail.host} (or a
 * JNDI name) is set. With neither set, no bean exists at all — which breaks {@link SmtpMailProvider}'s
 * constructor injection outright, even though an unconfigured SMTP provider is meant to be a normal,
 * non-fatal state (see {@link MailProvider#isConfigured()}).
 *
 * <p>This bean fills that gap: it always exists, built from whatever {@code spring.mail.*} happens to
 * be set (a blank host included). {@link SmtpMailProvider#isConfigured()} — not bean presence — is
 * what stops it from being used while unconfigured.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailSenderConfig {

    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public JavaMailSender javaMailSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        if (properties.getPort() != null) {
            sender.setPort(properties.getPort());
        }
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        if (properties.getProtocol() != null) {
            sender.setProtocol(properties.getProtocol());
        }
        if (properties.getDefaultEncoding() != null) {
            sender.setDefaultEncoding(properties.getDefaultEncoding().name());
        }
        if (!properties.getProperties().isEmpty()) {
            Properties javaMailProperties = new Properties();
            javaMailProperties.putAll(properties.getProperties());
            sender.setJavaMailProperties(javaMailProperties);
        }
        return sender;
    }
}
