package com.clele.parts.mcp;

import java.util.Set;

/**
 * What a verified MCP credential resolves to, whichever kind it was: who the request acts as, the
 * organisation it may read, and the authorities that user holds there.
 *
 * <p>An API key and an OAuth access token differ entirely in how they are obtained and nowhere in
 * what they confer, which is why both end here.
 */
public record McpPrincipal(String email, Long organisationId, Set<String> authorities) {
}
