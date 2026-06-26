package com.clele.parts.service;

import com.clele.parts.dto.UnreadChangesDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChangesService {

    @Value("${app.changes.dir:./changes}")
    private String changesDir;

    private final CurrentUserService currentUserService;
    private final AppUserRepository userRepository;

    /** Matches relative img src (not already absolute or root-relative). Group 1 = prefix up to src value, group 2 = optional "./", group 3 = filename, group 4 = closing quote. */
    private static final Pattern IMG_SRC = Pattern.compile(
            "(<img\\b[^>]*?\\ssrc=[\"'])(?!https?://|/)(\\./)?(\\S[^\"']*?)([\"'])",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Returns the 8-digit date of the newest changelog entry, or null if there are none. */
    public String getLatestDate() {
        File dir = new File(changesDir);
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().matches("\\d{8}\\.html"));
        if (files == null || files.length == 0) return null;
        return Arrays.stream(files)
                .map(f -> f.getName().replace(".html", ""))
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    public UnreadChangesDTO getUnreadChanges() {
        AppUser user = currentUserService.current();
        String lastRead = user.getLastReadChanges();

        File dir = new File(changesDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return new UnreadChangesDTO("", null, 0);
        }

        File[] files = dir.listFiles(f -> f.isFile() && f.getName().matches("\\d{8}\\.html"));
        if (files == null || files.length == 0) {
            return new UnreadChangesDTO("", null, 0);
        }

        List<File> unread = Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName))
                .filter(f -> {
                    String date = f.getName().replace(".html", "");
                    return lastRead == null || date.compareTo(lastRead) > 0;
                })
                .toList();

        if (unread.isEmpty()) {
            return new UnreadChangesDTO("", null, 0);
        }

        String latestDate = unread.getLast().getName().replace(".html", "");

        StringBuilder html = new StringBuilder();
        for (File f : unread) {
            String date = f.getName().replace(".html", "");
            String formatted = formatDate(date);
            html.append("<div class=\"changelog-entry\">");
            html.append("<h3 class=\"changelog-date\">").append(formatted).append("</h3>");
            html.append(rewriteImageSrc(readFile(f)));
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
        File file = new File(changesDir, filename);
        if (!file.exists() || !file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found: " + filename);
        }
        return Files.readAllBytes(file.toPath());
    }

    private String rewriteImageSrc(String html) {
        Matcher m = IMG_SRC.matcher(html);
        return m.replaceAll(mr ->
                Matcher.quoteReplacement(mr.group(1) + "/api/changes/images/" + mr.group(3) + mr.group(4)));
    }

    private String readFile(File f) {
        try {
            return Files.readString(f.toPath());
        } catch (IOException e) {
            log.warn("Could not read changelog file {}: {}", f.getName(), e.getMessage());
            return "<!-- Error reading " + f.getName() + " -->";
        }
    }

    /** Formats an 8-digit date string (YYYYMMDD) to a readable form like "June 23, 2026". */
    private String formatDate(String date) {
        if (date == null || date.length() != 8) return date;
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(4, 6));
            int day = Integer.parseInt(date.substring(6, 8));
            String[] months = {"January","February","March","April","May","June",
                    "July","August","September","October","November","December"};
            return months[month - 1] + " " + day + ", " + year;
        } catch (NumberFormatException e) {
            return date;
        }
    }
}
