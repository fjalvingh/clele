package com.clele.parts.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Proxies external image URLs through the backend so the browser is not subject
 * to third-party hotlink restrictions or rate-limiting (e.g. Wikimedia Commons).
 */
@RestController
public class ImageProxyController {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "upload.wikimedia.org",
            "commons.wikimedia.org",
            "external-content.duckduckgo.com"
    );

    private final RestTemplate restTemplate;

    public ImageProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/api/image-proxy")
    public ResponseEntity<byte[]> proxy(@RequestParam String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }

        if (!ALLOWED_HOSTS.contains(uri.getHost())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Host not in allowlist: " + uri.getHost());
        }

        HttpHeaders outgoing = new HttpHeaders();
        outgoing.set("User-Agent",
                "Mozilla/5.0 (compatible; CleleBot/1.0; +https://github.com/clele)");
        outgoing.set("Referer", "https://en.wikipedia.org/");
        outgoing.setAccept(List.of(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG,
                MediaType.IMAGE_GIF, MediaType.ALL));

        ResponseEntity<byte[]> upstream;
        try {
            upstream = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(outgoing), byte[].class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to fetch image: " + e.getMessage());
        }

        MediaType contentType = upstream.getHeaders().getContentType();
        if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM;

        HttpHeaders response = new HttpHeaders();
        response.setContentType(contentType);
        response.setCacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)));

        return new ResponseEntity<>(upstream.getBody(), response, HttpStatus.OK);
    }
}
