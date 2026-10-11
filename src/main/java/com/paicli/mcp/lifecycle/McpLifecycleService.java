package com.paicli.mcp.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.jsonrpc.McpSessionResult;
import com.paicli.mcp.manifest.McpConfigManifest;
import com.paicli.mcp.transport.McpPersistedSession;
import com.paicli.state.McpSessionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class McpLifecycleService {
    private static final String CLIENT_NAME = "PaiCLI";
    private static final int SERVER_INFO_FIELD_MAX = 256;
    private static final int SERVER_INFO_MAX = 4096;
    private static final int INSTRUCTIONS_MAX = 4096;
    private static final int CAPABILITY_SUMMARY_MAX = 4096;
    private final ObjectMapper json = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public McpLifecycleSession initialize(String sessionId, String manifestId, String runScope,
                                          String occurrence, int attempt, McpServerConfig config,
                                          McpConfigManifest manifest, McpSessionStore store,
                                          Map<String, String> hostEnvironment) throws McpLifecycleException {
        McpPersistedSession persisted = null;
        try {
            persisted = McpPersistedSession.start(sessionId, manifestId, runScope, occurrence, attempt,
                    config, manifest, store, hostEnvironment);
            ObjectNode params = json.createObjectNode();
            params.put("protocolVersion", McpServerConfig.PROTOCOL_VERSION);
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", CLIENT_NAME).put("version", applicationVersion());
            McpSessionResult response = persisted.jsonRpc().sendInitialize(params,
                            Duration.ofMillis(config.limits().startupTimeoutMs()))
                    .get(config.limits().startupTimeoutMs() + 1000L, TimeUnit.MILLISECONDS);
            if (response.status() != McpSessionResult.Status.SUCCEEDED)
                throw new McpLifecycleException(response.category() == null ? "INITIALIZE_FAILED" : response.category(), "MCP initialize failed");
            Negotiated negotiated = validate(response.result());
            McpLifecycleSession lifecycle = new McpLifecycleSession(persisted, config, manifest,
                    negotiated.listChanged(), negotiated.capabilitySummary());
            persisted.jsonRpc().onNotification(lifecycle::acceptNotification);
            persisted.jsonRpc().sendInitialized();
            persisted.markReady(McpServerConfig.PROTOCOL_VERSION, negotiated.serverInfoHash(), negotiated.capabilitySummary());
            return lifecycle;
        } catch (McpLifecycleException e) {
            failAndClose(persisted, e.category(), e.getMessage());
            throw e;
        } catch (Exception e) {
            failAndClose(persisted, "INITIALIZE_FAILED", "MCP initialize lifecycle failed");
            throw new McpLifecycleException("INITIALIZE_FAILED", "MCP initialize lifecycle failed", e);
        }
    }

    private Negotiated validate(JsonNode result) throws McpLifecycleException {
        if (result == null || !result.isObject()) throw failure("INVALID_INITIALIZE_RESULT", "initialize result must be an object");
        if (!McpServerConfig.PROTOCOL_VERSION.equals(result.path("protocolVersion").asText(null)))
            throw failure("UNSUPPORTED_PROTOCOL_VERSION", "server selected an unsupported MCP protocol version");
        JsonNode capabilities = result.get("capabilities");
        if (capabilities == null || !capabilities.isObject()) throw failure("INVALID_CAPABILITIES", "server capabilities must be an object");
        JsonNode tools = capabilities.get("tools");
        if (tools == null || !tools.isObject()) throw failure("NO_TOOLS_CAPABILITY", "server did not declare tools capability");
        JsonNode listChanged = tools.get("listChanged");
        if (listChanged != null && !listChanged.isBoolean()) throw failure("INVALID_TOOLS_CAPABILITY", "tools.listChanged must be boolean");
        JsonNode serverInfo = result.get("serverInfo");
        if (serverInfo == null || !serverInfo.isObject()) throw failure("INVALID_SERVER_INFO", "serverInfo must be an object");
        boundedRequiredText(serverInfo, "name"); boundedRequiredText(serverInfo, "version");
        if (canonical(serverInfo).length > SERVER_INFO_MAX) throw failure("SERVER_INFO_TOO_LARGE", "serverInfo exceeds bounds");
        JsonNode instructions = result.get("instructions");
        if (instructions != null && (!instructions.isTextual() || instructions.asText().getBytes(StandardCharsets.UTF_8).length > INSTRUCTIONS_MAX))
            throw failure("INSTRUCTIONS_TOO_LARGE", "server instructions exceed untrusted metadata bounds");
        byte[] capabilityBytes = canonical(capabilities);
        if (capabilityBytes.length > CAPABILITY_SUMMARY_MAX) throw failure("CAPABILITIES_TOO_LARGE", "capability metadata exceeds bounds");
        ObjectNode summary = json.createObjectNode();
        summary.put("tools", true);
        summary.put("toolsListChanged", listChanged != null && listChanged.asBoolean());
        summary.put("otherCapabilityCount", Math.max(0, capabilities.size() - 1));
        return new Negotiated(sha256(canonical(serverInfo)), jsonString(summary), listChanged != null && listChanged.asBoolean());
    }

    private void boundedRequiredText(JsonNode object, String field) throws McpLifecycleException {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > SERVER_INFO_FIELD_MAX)
            throw failure("INVALID_SERVER_INFO", "serverInfo fields must be bounded strings");
    }

    private byte[] canonical(JsonNode node) throws McpLifecycleException {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(canonicalNode(node)); }
        catch (Exception e) { throw new McpLifecycleException("METADATA_SERIALIZATION", "unable to hash MCP metadata", e); }
    }
    private String jsonString(JsonNode node) throws McpLifecycleException { return new String(canonical(node), StandardCharsets.UTF_8); }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private JsonNode canonicalNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = json.createObjectNode();
            java.util.List<String> names = new java.util.ArrayList<>(); node.fieldNames().forEachRemaining(names::add); java.util.Collections.sort(names);
            for (String name : names) out.set(name, canonicalNode(node.get(name)));
            return out;
        }
        if (node.isArray()) { var out = json.createArrayNode(); for (JsonNode child : node) out.add(canonicalNode(child)); return out; }
        return node.deepCopy();
    }
    private static McpLifecycleException failure(String category, String message) { return new McpLifecycleException(category, message); }
    private static String applicationVersion() {
        String version = McpLifecycleService.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }
    private static void failAndClose(McpPersistedSession persisted, String category, String detail) {
        if (persisted == null) return;
        persisted.jsonRpc().markUnhealthy(category, detail);
        try { persisted.close(); } catch (Exception ignored) { }
    }
    private record Negotiated(String serverInfoHash, String capabilitySummary, boolean listChanged) { }
}
