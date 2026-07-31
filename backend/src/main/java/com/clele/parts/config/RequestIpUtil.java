package com.clele.parts.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client IP extraction shared by browser-session and daemon-facing endpoints. Behind the prod
 * Apache reverse proxy {@code forward-headers-strategy: framework} already resolves
 * {@code getRemoteAddr()} correctly, but daemon self-registration/polling and the browser-facing
 * daemon list both need the same logic, so it lives in one place.
 */
public final class RequestIpUtil {

    private RequestIpUtil() {
    }

    public static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
