package com.clele.parts.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The Model Context Protocol endpoint: one POST speaking JSON-RPC 2.0, which is all the
 * "Streamable HTTP" transport requires of a server that never pushes anything on its own.
 *
 * <p><b>Read-only.</b> Every tool here answers questions about the catalogue; none of them writes.
 * That is the whole security model of the thing — an assistant given a key cannot edit a part,
 * move stock or change a spec, whatever it is asked to do.
 *
 * <p><b>Why hand-rolled and not an SDK.</b> The server side of this transport is a JSON-RPC
 * dispatcher over four methods ({@code initialize}, {@code ping}, {@code tools/list},
 * {@code tools/call}); the MCP Java SDK would bring a reactive stack and a Boot version this
 * project is not on. The protocol details that actually matter are pinned in {@link McpProtocol}.
 *
 * <p><b>No SSE and no sessions.</b> A GET (the client asking to open a server-initiated stream) is
 * answered 405, which the specification explicitly allows for a server with nothing to push; every
 * response is a single {@code application/json} body. Nothing is remembered between calls, so no
 * {@code Mcp-Session-Id} is issued and reconnecting costs nothing.
 *
 * <p>Authentication is the API key, checked by {@code McpApiKeyAuthFilter} before this is reached;
 * the key also decides which organisation the tools see.
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "MCP", description = "Model Context Protocol endpoint (read-only, API-key authenticated)")
public class McpController {

    private final ObjectMapper objectMapper;
    private final McpToolRegistry tools;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "JSON-RPC 2.0 request (initialize, ping, tools/list, tools/call)")
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode body) {
        // A batch is legal in JSON-RPC and was legal in MCP before 2025-06-18; older clients still
        // send one, and answering it is a few lines. Responses to notifications are dropped, so an
        // all-notification batch leaves nothing to return.
        if (body.isArray()) {
            ArrayNode responses = objectMapper.createArrayNode();
            body.forEach(element -> {
                ObjectNode response = handle(element);
                if (response != null) responses.add(response);
            });
            return responses.isEmpty()
                    ? ResponseEntity.accepted().build()
                    : ResponseEntity.ok(responses);
        }
        ObjectNode response = handle(body);
        return response == null
                ? ResponseEntity.accepted().build()
                : ResponseEntity.ok(response);
    }

    /**
     * The client asking to open a server-initiated SSE stream. This server never initiates
     * anything, and the specification lets it say so.
     */
    @GetMapping
    @Operation(summary = "Not supported — this server never pushes messages (405)")
    public ResponseEntity<JsonNode> stream() {
        return ResponseEntity.status(405).build();
    }

    /** Session teardown. There is no session to tear down, so this is simply done. */
    @DeleteMapping
    @Operation(summary = "No-op — the server is stateless")
    public ResponseEntity<Void> end() {
        return ResponseEntity.noContent().build();
    }

    /**
     * One JSON-RPC message. Returns null when the message was a notification, which by the spec
     * gets no reply at all — not even an error.
     */
    private ObjectNode handle(JsonNode message) {
        JsonNode id = message.get("id");
        boolean notification = id == null || id.isNull();
        String method = message.path("method").asText("");
        if (notification) {
            return null;
        }
        try {
            JsonNode params = message.path("params");
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, objectMapper.createObjectNode());
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> result(id, tools.call(
                        params.path("name").asText(""), params.path("arguments")));
                default -> error(id, McpProtocol.METHOD_NOT_FOUND, "Unknown method: " + method);
            };
        } catch (McpToolRegistry.UnknownToolException e) {
            return error(id, McpProtocol.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            // A failure inside a tool is reported as a tool result (see McpToolRegistry.call), so
            // reaching here means the dispatcher itself broke — worth a log line.
            log.warn("MCP request '{}' failed", method, e);
            return error(id, McpProtocol.INTERNAL_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * The handshake. The protocol version is negotiated rather than asserted: a version we know is
     * echoed back, anything else is answered with our newest and the client decides whether it can
     * live with that.
     */
    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String agreed = McpProtocol.SUPPORTED_VERSIONS.contains(requested)
                ? requested
                : McpProtocol.LATEST_VERSION;

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", agreed);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", McpProtocol.SERVER_NAME);
        serverInfo.put("title", McpProtocol.SERVER_TITLE);
        serverInfo.put("version", McpProtocol.SERVER_VERSION);
        result.put("instructions", McpProtocol.INSTRUCTIONS);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode array = result.putArray("tools");
        for (McpToolRegistry.Tool tool : tools.list()) {
            ObjectNode node = array.addObject();
            node.put("name", tool.name());
            node.put("title", tool.title());
            node.put("description", tool.description());
            node.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private ObjectNode result(JsonNode id, JsonNode payload) {
        ObjectNode response = envelope(id);
        response.set("result", payload);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = envelope(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ObjectNode envelope(JsonNode id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        return response;
    }
}
