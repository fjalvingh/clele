package com.clele.parts.controller;

import com.clele.parts.dto.MarkChangesReadRequest;
import com.clele.parts.dto.UnreadChangesDTO;
import com.clele.parts.service.ChangesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/changes")
@RequiredArgsConstructor
public class ChangesController {

    private final ChangesService changesService;

    /** Returns merged HTML of all changelog entries the current user has not yet read. */
    @GetMapping("/unread")
    public UnreadChangesDTO getUnread() {
        return changesService.getUnreadChanges();
    }

    /** Records that the user has read up to and including the given date. */
    @PostMapping("/mark-read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@RequestBody MarkChangesReadRequest request) {
        changesService.markRead(request.getDate());
    }

    /** Serves image files from the changes directory so HTML snippets can embed them. */
    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> getImage(@PathVariable String filename) throws IOException {
        byte[] data = changesService.getImage(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(inferContentType(filename)))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(data);
    }

    private String inferContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
