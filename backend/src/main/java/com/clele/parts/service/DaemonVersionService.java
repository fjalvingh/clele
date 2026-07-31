package com.clele.parts.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The print-daemon version this build of the app ships and therefore expects daemons to be
 * running. Written to {@code daemon-version.txt} by the Maven build from the timestamp of the
 * last commit touching {@code daemon/}, so it only changes when the daemon actually changes.
 *
 * <p>Unknown when the app was built without that step; in that case no version warning is shown
 * rather than a bogus one.
 */
@Service
@Slf4j
public class DaemonVersionService {

    private static final String RESOURCE = "daemon-version.txt";
    private static final String UNKNOWN = "unknown";

    /** Expected daemon version, or null when this build doesn't know one. */
    @Getter
    private String expectedVersion;

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.info("No {} on the classpath — daemon version checking disabled", RESOURCE);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            String value = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (value.isEmpty() || UNKNOWN.equals(value)) {
                log.info("Daemon version is '{}' — version checking disabled", value);
                return;
            }
            expectedVersion = value;
            log.info("Expected print daemon version: {}", expectedVersion);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", RESOURCE, e.getMessage());
        }
    }

    /**
     * Whether a daemon reporting {@code reportedVersion} is out of date. False when either side is
     * unknown — an unverifiable version is not reported as a mismatch.
     */
    public boolean isOutdated(String reportedVersion) {
        if (expectedVersion == null || reportedVersion == null || reportedVersion.isBlank()) {
            return false;
        }
        return !expectedVersion.equals(reportedVersion.trim());
    }
}
