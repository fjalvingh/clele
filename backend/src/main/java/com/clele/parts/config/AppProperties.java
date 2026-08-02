package com.clele.parts.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * App-wide settings (configured under {@code app.*} in application.yml), exposed to the SPA via
 * {@code GET /api/settings}.
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private final Currency currency = new Currency();

    private final Mail mail = new Mail();

    /**
     * The name of the product as the outside world knows it. "Clele" is the internal code name —
     * it lives in package names, the repo and the database, and must never reach a screen or a
     * mail. Anything user-facing says this.
     */
    private String publicName = "Sortiment";

    /**
     * Public base URL of this installation (e.g. {@code https://parts.example.com}), used to build
     * the link in an invitation mail. Blank means "derive it from the current request".
     */
    private String baseUrl = "";

    /** The single currency the whole app reports prices in. */
    @Data
    public static class Currency {
        /** Currency name/code, e.g. {@code EUR}. */
        private String code = "EUR";
        /** Symbol used for display, e.g. {@code €}. */
        private String symbol = "€";
    }

    /** Outgoing mail. Only invitations are sent today. */
    @Data
    public static class Mail {
        /**
         * Which {@code MailProvider} delivers the mail: {@code smtp}, {@code mailersend}, or
         * {@code none} to log instead of send. This one setting is the whole provider switch.
         *
         * <p>Defaults to {@code smtp}, which with no {@code spring.mail.host} set is simply
         * unconfigured and logs — so an install that sent mail before still does, unchanged.
         */
        private String provider = "smtp";
        /** From address on invitation mails. */
        private String from = "no-reply@sortiment.local";
        /** Display name for the sender; blank to send the address alone. */
        private String fromName = "Sortiment";
        /** How long an invitation stays valid. */
        private int invitationExpiryDays = 14;

        private final Mailersend mailersend = new Mailersend();
    }

    /** Settings for the MailerSend HTTP provider (used when {@code app.mail.provider=mailersend}). */
    @Data
    public static class Mailersend {
        /** MailerSend API token. Blank means the provider is not configured. */
        private String apiKey = "";
        /** API root; only ever changed to point at a mock in tests. */
        private String baseUrl = "https://api.mailersend.com/v1";
    }
}
