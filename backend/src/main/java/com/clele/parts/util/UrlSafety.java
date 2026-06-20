package com.clele.parts.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for endpoints that fetch a user-supplied URL server-side (image proxy, image
 * from-url). Requires an http(s) URL and rejects any host that resolves to a loopback,
 * link-local, site-local (private), wildcard, or multicast address — plus the cloud metadata
 * IP 169.254.169.254 — so an authenticated caller cannot make the server reach internal
 * services or the instance metadata endpoint.
 */
public final class UrlSafety {

    private UrlSafety() {}

    /** Validate the URL and return its parsed {@link URI}, or throw 400 if it is unsafe. */
    public static URI validateExternalHttpUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only HTTP(S) URLs allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL has no host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown host");
        }

        for (InetAddress addr : addresses) {
            if (isDisallowed(addr)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "URL resolves to a non-public address");
            }
        }
        return uri;
    }

    private static boolean isDisallowed(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()
                // Cloud instance metadata (covered by link-local for IPv4, explicit for safety).
                || "169.254.169.254".equals(addr.getHostAddress());
    }
}
