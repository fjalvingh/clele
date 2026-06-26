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
    private final Changes changes = new Changes();

    /** The single currency the whole app reports prices in. */
    @Data
    public static class Currency {
        /** Currency name/code, e.g. {@code EUR}. */
        private String code = "EUR";
        /** Symbol used for display, e.g. {@code €}. */
        private String symbol = "€";
    }

    /** Changelog notification settings. */
    @Data
    public static class Changes {
        /** Directory containing {@code YYYYMMDD.html} changelog entries and their images. */
        private String dir = "./changes";
    }
}
