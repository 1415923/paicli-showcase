package com.paicli.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.jsonrpc.McpJsonRpcMessage;
import com.paicli.mcp.jsonrpc.McpSessionResult;
import com.paicli.mcp.lifecycle.McpLifecycleSession;
import com.paicli.mcp.schema.*;
import com.paicli.state.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class McpToolCatalogService {
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final int MAX_CURSOR_BYTES = 1024;
    private static final int MAX_DESCRIPTOR_BYTES = 512 * 1024;
    private static final int MAX_OUTPUT_SCHEMA_DEPTH = 32;
    private final McpToolCatalogStore catalogs;
    private final McpToolStore tools;
    private final ObjectMapper json = new ObjectMapper();

    public McpToolCatalogService(McpToolCatalogStore catalogs, McpToolStore tools) {
        this.catalogs = Objects.requireNonNull(catalogs);
        this.tools = Objects.requireNonNull(tools);
    }

    public McpCatalogBuildResult build(McpLifecycleSession lifecycle) throws McpCatalogException {
        try {
            McpSessionStore.SessionRecord session = lifecycle.persisted().store().findById(lifecycle.persisted().sessionId())
                    .orElseThrow(() -> new McpCatalogException("SESSION_NOT_FOUND", "MCP session metadata is missing"));
            if (!"READY".equals(session.status()) || !McpServerConfig.PROTOCOL_VERSION.equals(session.protocolVersion()))
                throw new McpCatalogException("SESSION_NOT_READY", "tools/list requires a READY negotiated session");
            if (lifecycle.persisted().jsonRpc().state() != com.paicli.mcp.jsonrpc.McpJsonRpcSession.State.READY
                    || !lifecycle.persisted().isProcessAlive())
                throw new McpCatalogException("SESSION_NOT_LIVE", "catalog cannot be used after session process or protocol failure");
            Optional<McpToolCatalogStore.CatalogRecord> existing = catalogs.findBySession(session.sessionId());
            if (existing.isPresent()) {
                if ("FROZEN".equals(existing.get().status())) return result(existing.get(), true);
                if ("STALE".equals(existing.get().status())) throw new McpCatalogException("CATALOG_STALE", "stale catalog requires a new session or explicit future refresh");
                if ("INVALID".equals(existing.get().status())) throw new McpCatalogException("CATALOG_INVALID", "invalid catalog cannot be reused");
                throw new McpCatalogException("BUILD_IN_PROGRESS", "session already owns a building catalog artifact");
            }
            if (lifecycle.listChangedObserved())
                throw new McpCatalogException("CATALOG_STALE_BEFORE_BUILD", "server changed tools before catalog build acquired ownership");
            String catalogId = UUID.randomUUID().toString();
            if (!catalogs.acquireBuildingCatalog(catalogId, session.sessionId(), session.manifestId(),
                    session.serverId(), session.protocolVersion()))
                throw new McpCatalogException("BUILD_IN_PROGRESS", "another catalog build owns this session");

            AtomicBoolean changed = new AtomicBoolean();
            lifecycle.addNotificationListener(notification -> handleNotification(lifecycle, catalogId, changed, notification));
            try {
                Collected collected = collect(lifecycle, catalogId, changed);
                if (changed.get()) throw new McpCatalogException("CATALOG_CHANGED_DURING_BUILD", "tool catalog changed while building");
                catalogs.freezeCatalogAtomically(catalogId, collected.records(), collected.catalogHash(),
                        collected.pageCount(), collected.totalSchemaBytes());
                McpToolCatalogStore.CatalogRecord frozen = catalogs.findById(catalogId).orElseThrow();
                if (changed.get() || lifecycle.listChangedObserved() || !"FROZEN".equals(frozen.status()))
                    throw new McpCatalogException("CATALOG_CHANGED_DURING_BUILD", "tool catalog changed before freeze completed");
                return result(frozen, false);
            } catch (Exception e) {
                markInvalidIfBuilding(catalogId, e instanceof McpCatalogException x ? x.category() : "CATALOG_BUILD_FAILED");
                if (e instanceof McpCatalogException x) throw x;
                throw new McpCatalogException("CATALOG_BUILD_FAILED", "unable to build MCP catalog", e);
            }
        } catch (McpCatalogException e) { throw e; }
        catch (Exception e) { throw new McpCatalogException("CATALOG_STORE_FAILED", "unable to access MCP catalog state", e); }
    }

    private Collected collect(McpLifecycleSession lifecycle, String catalogId, AtomicBoolean changed) throws Exception {
        McpServerConfig config = lifecycle.config();
        Set<String> allowed = Set.copyOf(config.allowedToolNames());
        Set<String> foundAllowed = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> cursors = new HashSet<>();
        List<NormalizedTool> normalized = new ArrayList<>();
        String cursor = null;
        int pages = 0, totalBytes = 0, schemaBytes = 0;
        do {
            if (++pages > config.limits().maxCatalogPages()) throw error("MAX_CATALOG_PAGES", "tools/list exceeded page budget");
            ObjectNode params = json.createObjectNode(); if (cursor != null) params.put("cursor", cursor);
            McpSessionResult response = lifecycle.persisted().jsonRpc().sendToolsList(params,
                            Duration.ofMillis(config.limits().requestTimeoutMs()))
                    .get(config.limits().requestTimeoutMs() + 1000L, TimeUnit.MILLISECONDS);
            if (response.status() != McpSessionResult.Status.SUCCEEDED)
                throw error(response.category() == null ? "TOOLS_LIST_FAILED" : response.category(), "tools/list request failed");
            JsonNode result = response.result();
            if (result == null || !result.isObject() || !result.has("tools") || !result.get("tools").isArray())
                throw protocol(lifecycle, "INVALID_TOOLS_LIST", "tools/list result must contain tools array");
            byte[] pageBytes = json.writeValueAsBytes(result);
            totalBytes += pageBytes.length;
            if (totalBytes > config.limits().maxCatalogBytes()) throw error("CATALOG_TOO_LARGE", "catalog exceeds byte budget");
            JsonNode next = result.get("nextCursor");
            String nextCursor;
            if (next == null || next.isNull()) nextCursor = null;
            else {
                if (!next.isTextual() || next.asText().isBlank() || next.asText().getBytes(StandardCharsets.UTF_8).length > MAX_CURSOR_BYTES)
                    throw error("INVALID_CURSOR", "tools/list cursor is invalid");
                nextCursor = next.asText();
                if (!cursors.add(nextCursor)) throw error("CURSOR_CYCLE", "tools/list cursor repeated");
            }
            for (JsonNode descriptor : result.get("tools")) {
                if (!descriptor.isObject()) throw error("INVALID_TOOL_DESCRIPTOR", "tool descriptor must be an object");
                if (normalized.size() >= config.limits().maxTools()) throw error("MAX_TOOLS", "catalog exceeds tool count budget");
                byte[] descriptorBytes = json.writeValueAsBytes(descriptor);
                if (descriptorBytes.length > Math.min(MAX_DESCRIPTOR_BYTES, config.limits().maxCatalogBytes()))
                    throw error("TOOL_DESCRIPTOR_TOO_LARGE", "tool descriptor exceeds byte budget");
                String name = descriptor.path("name").asText(null);
                if (name == null || !TOOL_NAME.matcher(name).matches()) throw error("INVALID_TOOL_NAME", "MCP tool name is invalid");
                if (!names.add(name)) throw error("DUPLICATE_TOOL_NAME", "MCP tool name is duplicated");
                boolean exposed = allowed.contains(name);
                if (exposed) foundAllowed.add(name);
                NormalizedTool tool = normalizeTool(config, descriptor, name, exposed);
                normalized.add(tool); schemaBytes += tool.schemaBytes();
                if (schemaBytes > config.limits().maxCatalogBytes()) throw error("CATALOG_SCHEMA_BUDGET", "catalog schema bytes exceed budget");
            }
            cursor = nextCursor;
            if (changed.get()) throw error("CATALOG_CHANGED_DURING_BUILD", "tool catalog changed while paginating");
        } while (cursor != null);
        if (!foundAllowed.equals(allowed)) throw error("ALLOWLIST_TOOL_MISSING", "trusted allowlist contains tools absent from server catalog");
        normalized.sort(Comparator.comparing(NormalizedTool::originalName));
        Set<String> aliases = new HashSet<>();
        for (NormalizedTool tool : normalized)
            if (!aliases.add(tool.exposedName())) throw error("TOOL_ALIAS_COLLISION", "MCP alias collision");
        String catalogHash = catalogHash(lifecycle, normalized);
        List<McpToolStore.ToolRecord> records = new ArrayList<>();
        for (NormalizedTool tool : normalized) records.add(tool.toRecord(catalogId, lifecycle.config().serverId(), catalogHash));
        return new Collected(List.copyOf(records), catalogHash, pages, schemaBytes);
    }

    private NormalizedTool normalizeTool(McpServerConfig config, JsonNode descriptor, String name, boolean exposed) throws Exception {
        String title = boundedText(descriptor.get("title"), 256, "title");
        String description = boundedText(descriptor.get("description"), 2048, "description");
        String exposedName = com.paicli.mcp.tool.McpToolNames.alias(config.serverId(), name);
        JsonNode taskSupport = descriptor.path("execution").path("taskSupport");
        String taskFact = taskSupport.isMissingNode() ? "missing" : taskSupport.asText("invalid");
        if (!Set.of("missing", "forbidden", "optional", "required").contains(taskFact))
            throw error("INVALID_TASK_EXECUTION", "execution.taskSupport is invalid");
        if (exposed && ("optional".equals(taskFact) || "required".equals(taskFact)))
            throw error("UNSUPPORTED_TASK_EXECUTION", "allowlisted tool requires unsupported task execution");
        McpNormalizedSchema schema;
        String rejection = exposed ? null : "NOT_ALLOWLISTED";
        try {
            schema = new McpInputSchemaNormalizer(config.limits().maxSingleSchemaBytes()).normalize(descriptor.get("inputSchema"));
        } catch (McpSchemaException unsupported) {
            exposed = false;
            rejection = unsupported.category() + ": " + unsupported.getMessage();
            schema = new McpInputSchemaNormalizer(config.limits().maxSingleSchemaBytes()).normalize(json.createObjectNode().put("type", "object"));
        }
        String outputHash = descriptor.has("outputSchema") ? boundedJsonHash(descriptor.get("outputSchema"), config.limits().maxSingleSchemaBytes()) : "none";
        ObjectNode fact = json.createObjectNode();
        fact.put("name", name); fact.put("exposedName", exposedName); fact.put("exposure", exposed ? "EXPOSED" : "REJECTED");
        fact.put("rejection", rejection == null ? "" : rejection); fact.put("schemaHash", schema.schemaHash());
        fact.put("originalInputHash", sha256(json.writeValueAsBytes(canonical(descriptor.path("inputSchema")))));
        fact.put("title", title == null ? "" : title); fact.put("description", description == null ? "" : description);
        fact.put("taskSupport", taskFact); fact.put("outputSchemaHash", outputHash);
        return new NormalizedTool(name, exposedName, title, description, schema, exposed ? "EXPOSED" : "REJECTED",
                rejection, sha256(json.writeValueAsBytes(fact)));
    }

    private String boundedJsonHash(JsonNode node, int maxBytes) throws Exception {
        if (node == null || !node.isObject()) throw error("INVALID_OUTPUT_SCHEMA", "outputSchema must be an object");
        if (depth(node, 1) > MAX_OUTPUT_SCHEMA_DEPTH) throw error("OUTPUT_SCHEMA_TOO_DEEP", "outputSchema exceeds depth budget");
        byte[] bytes = json.writeValueAsBytes(canonical(node));
        if (bytes.length > maxBytes) throw error("OUTPUT_SCHEMA_TOO_LARGE", "outputSchema exceeds byte budget");
        return sha256(bytes);
    }

    private void handleNotification(McpLifecycleSession lifecycle, String catalogId, AtomicBoolean changed,
                                    McpJsonRpcMessage.Notification notification) {
        if (!"notifications/tools/list_changed".equals(notification.method())) return;
        changed.set(true);
        try {
            McpToolCatalogStore.CatalogRecord catalog = catalogs.findById(catalogId).orElse(null);
            if (catalog == null) return;
            if ("FROZEN".equals(catalog.status())) catalogs.markCatalogStale(catalogId);
            else if ("BUILDING".equals(catalog.status())) catalogs.markCatalogInvalid(catalogId, "TOOLS_LIST_CHANGED");
        } catch (Exception e) {
            lifecycle.persisted().jsonRpc().markUnhealthy("CATALOG_NOTIFICATION_PERSISTENCE", "unable to persist tools/list_changed");
        }
    }

    private McpCatalogBuildResult result(McpToolCatalogStore.CatalogRecord catalog, boolean reused) throws Exception {
        List<McpToolStore.ToolRecord> records = List.copyOf(tools.findAll(catalog.catalogId()));
        if (records.size() != catalog.toolCount()) throw error("CATALOG_ARTIFACT_MISMATCH", "catalog tool count no longer matches frozen artifact");
        return new McpCatalogBuildResult(catalog, records, reused);
    }
    private void markInvalidIfBuilding(String id, String reason) { try { if (catalogs.findById(id).map(x -> "BUILDING".equals(x.status())).orElse(false)) catalogs.markCatalogInvalid(id, reason); } catch (Exception ignored) { } }
    private McpCatalogException protocol(McpLifecycleSession lifecycle, String category, String message) { lifecycle.persisted().jsonRpc().markUnhealthy(category, message); return error(category, message); }
    private static McpCatalogException error(String category, String message) { return new McpCatalogException(category, message); }
    private static String boundedText(JsonNode node, int max, String field) throws McpCatalogException { if (node == null || node.isNull()) return null; if (!node.isTextual()) throw error("INVALID_" + field.toUpperCase(), field + " must be text"); String clean=node.asText().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", ""); return clean.substring(0,Math.min(max,clean.length())); }
    private static int depth(JsonNode node,int d){int max=d;if(node.isContainerNode())for(JsonNode child:node)max=Math.max(max,depth(child,d+1));return max;}
    private JsonNode canonical(JsonNode node){if(node.isObject()){ObjectNode out=json.createObjectNode();List<String> keys=new ArrayList<>();node.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)out.set(key,canonical(node.get(key)));return out;}if(node.isArray()){var out=json.createArrayNode();for(JsonNode child:node)out.add(canonical(child));return out;}return node.deepCopy();}
    private static String sha256(byte[] bytes){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String catalogHash(McpLifecycleSession lifecycle,List<NormalizedTool> tools){StringBuilder b=new StringBuilder().append(lifecycle.config().serverId()).append('\n').append(lifecycle.manifest().configHash()).append('\n').append(McpServerConfig.PROTOCOL_VERSION).append('\n');for(NormalizedTool tool:tools)b.append(tool.descriptorHash()).append('\n');return sha256(b.toString().getBytes(StandardCharsets.UTF_8));}
    private record Collected(List<McpToolStore.ToolRecord> records,String catalogHash,int pageCount,int totalSchemaBytes){}
    private record NormalizedTool(String originalName,String exposedName,String title,String description,McpNormalizedSchema schema,String exposure,String rejection,String descriptorHash){int schemaBytes(){return schema.byteCount();}McpToolStore.ToolRecord toRecord(String catalogId,String server,String catalogHash){String id="mcp-tool-"+sha256((catalogId+":"+catalogHash+":"+originalName).getBytes(StandardCharsets.UTF_8)).substring(0,32);return new McpToolStore.ToolRecord(id,catalogId,server,originalName,exposedName,title,description,schema.canonicalJson(),schema.schemaHash(),exposure,rejection);}}
}
