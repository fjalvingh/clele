package com.clele.parts.service;

import com.clele.parts.dto.UnreadChangesDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChangesService {

    private static final String CLASSPATH_PATTERN = "classpath:changes/*.html";

    private final CurrentUserService currentUserService;
    private final AppUserRepository userRepository;

    /** Matches relative img src (not already absolute or root-relative). */
    private static final Pattern IMG_SRC = Pattern.compile(
            "(<img\\b[^>]*?\\ssrc=[\"'])(?!https?://|/)(\\./)?(\\S[^\"']*?)([\"'])",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Returns the 8-digit date of the newest changelog entry, or null if there are none. */
    public String getLatestDate() {
        return Arrays.stream(listResources())
                .map(r -> r.getFilename().replace(".html", ""))
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    public UnreadChangesDTO getUnreadChanges() {
        AppUser user = currentUserService.current();
        String lastRead = user.getLastReadChanges();

        Resource[] resources = listResources();
        if (resources.length == 0) {
            return new UnreadChangesDTO("", null, 0);
        }

        List<Resource> unread = Arrays.stream(resources)
                .filter(r -> r.getFilename() != null && r.getFilename().matches("\\d{8}\\.html"))
                .sorted(Comparator.comparing(Resource::getFilename))
                .filter(r -> {
                    String date = r.getFilename().replace(".html", "");
                    return lastRead == null || date.compareTo(lastRead) > 0;
                })
                .toList();

        if (unread.isEmpty()) {
            return new UnreadChangesDTO("", null, 0);
        }

        String latestDate = unread.getLast().getFilename().replace(".html", "");

        StringBuilder html = new StringBuilder();
        for (Resource r : unread) {
            String date = r.getFilename().replace(".html", "");
            html.append("<div class=\"changelog-entry\">");
            html.append("<h3 class=\"changelog-date\">").append(formatDate(date)).append("</h3>");
            html.append(rewriteImageSrc(readResource(r)));
            html.append("</div>");
        }

        return new UnreadChangesDTO(html.toString(), latestDate, unread.size());
    }

    @Transactional
    public void markRead(String date) {
        AppUser user = currentUserService.current();
        user.setLastReadChanges(date);
        userRepository.save(user);
    }

    public byte[] getImage(String filename) throws IOException {
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:changes/" + filename);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found: " + filename);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private Resource[] listResources() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(CLASSPATH_PATTERN);
        } catch (IOException e) {
            log.warn("Could not list changelog resources: {}", e.getMessage());
            return new Resource[0];
        }
    }

    private String rewriteImageSrc(String html) {
        return IMG_SRC.matcher(html).replaceAll(mr ->
                Matcher.quoteReplacement(mr.group(1) + "/api/changes/images/" + mr.group(3) + mr.group(4)));
    }

    private String readResource(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read changelog resource {}: {}", r.getFilename(), e.getMessage());
            return "<!-- Error reading " + r.getFilename() + " -->";
        }
    }

    /** Formats an 8-digit date string (YYYYMMDD) to a readable form like "June 23, 2026". */
    private String formatDate(String date) {
        if (date == null || date.length() != 8) return date;
        try {
            int year  = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(4, 6));
            int day   = Integer.parseInt(date.substring(6, 8));
            String[] months = {"January","February","March","April","May","June",
                    "July","August","September","October","November","December"};
            return months[month - 1] + " " + day + ", " + year;
        } catch (NumberFormatException e) {
            return date;
        }
    }
}
