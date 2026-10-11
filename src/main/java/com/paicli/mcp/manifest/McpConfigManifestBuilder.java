package com.paicli.mcp.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.mcp.config.McpEnvironmentReference;
import com.paicli.mcp.config.McpServerConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpConfigManifestBuilder {
    public static final String FORMAT = "1";
    public static final String APPROVAL_POLICY = "mcp-on-request-v1";
    public static final String SANITIZER_POLICY = "mcp-output-v1";
    public static final String SCHEMA_POLICY = "mcp-schema-v3-draft07-202012-subset";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public McpConfigManifest build(McpServerConfig config) throws IOException {
        Path executable = config.executable().toRealPath();
        Path working = config.workingDirectory().toRealPath();
        String protocols = json(config.supportedProtocolVersions());
        List<Map<String, String>> refs = config.environmentReferences().stream()
                .sorted((a,b) -> (a.source()+":"+a.name()).compareTo(b.source()+":"+b.name()))
                .map(r -> Map.of("name", r.name(), "source", r.source())).toList();
        String refsJson = json(refs);
        String toolsJson = json(config.allowedToolNames().stream().sorted().toList());
        String argsHash = hash(json(config.args()));
        var l = config.limits();
        Map<String,Object> canonical = new LinkedHashMap<>();
        canonical.put("approval_policy_version", APPROVAL_POLICY);
        canonical.put("args_hash", argsHash);
        canonical.put("artifact_content_hash", com.paicli.mcp.config.McpArtifactSnapshot.hash(config));
        canonical.put("enabled", config.enabled());
        canonical.put("environment_references", refs);
        canonical.put("executable_basename", executable.getFileName().toString());
        canonical.put("executable_content_hash", hash(Files.readAllBytes(executable)));
        canonical.put("executable_identity_hash", hash(executable.toString()));
        canonical.put("limits", l);
        canonical.put("manifest_format_version", FORMAT);
        canonical.put("protocol_versions", config.supportedProtocolVersions());
        canonical.put("sanitizer_policy_version", SANITIZER_POLICY);
        canonical.put("schema_policy_version", SCHEMA_POLICY);
        canonical.put("server_id", config.serverId());
        canonical.put("tool_allowlist", config.allowedToolNames().stream().sorted().toList());
        canonical.put("transport", config.transport());
        canonical.put("working_directory_identity_hash", hash(working.toString()));
        String canonicalJson = json(canonical);
        return new McpConfigManifest(FORMAT, config.serverId(), config.enabled(), config.transport(),
                protocols, hash(canonicalJson), hash(executable.toString()), hash(Files.readAllBytes(executable)),
                executable.getFileName().toString(), hash(working.toString()), argsHash, refsJson, toolsJson,
                l.startupTimeoutMs(), l.requestTimeoutMs(), l.shutdownTimeoutMs(), l.maxPendingRequests(),
                l.maxStdoutLineBytes(), l.maxStderrBytes(), l.maxCatalogPages(), l.maxTools(),
                l.maxCatalogBytes(), l.maxSingleSchemaBytes(), l.maxOutputBytes(), APPROVAL_POLICY,
                SANITIZER_POLICY, SCHEMA_POLICY, canonicalJson);
    }

    private static String json(Object value) throws JsonProcessingException { return JSON.writeValueAsString(value); }
    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
