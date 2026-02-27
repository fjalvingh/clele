package com.clele.parts.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Proxies external image URLs through the backend so the browser is not subject
 * to third-party hotlink restrictions or rate-limiting (e.g. Wikimedia Commons).
 */
@RestController
public class ImageProxyController {

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

        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only HTTP(S) URLs allowed");
        }

        HttpHeaders outgoing = new HttpHeaders();
        outgoing.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
        outgoing.set("Accept", "image/*, */*;q=0.8");
        outgoing.set("Accept-Language", "en-US,en;q=0.5");
        outgoing.set("Referer", uri.getScheme() + "://" + uri.getHost() + "/");

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
