package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class McpConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path trustedRoot;
    private final boolean userConfiguration;

    public McpConfigLoader(Path trustedRoot) throws IOException {
        this(trustedRoot, false);
    }

    /** Parsing user configuration grants no execution; caller must confirm its complete manifest. */
    public McpConfigLoader(Path trustedRoot, boolean userConfiguration) throws IOException {
        this.trustedRoot = trustedRoot.toRealPath();
        this.userConfiguration = userConfiguration;
    }

    public McpClientConfig load(Path configFile) throws IOException {
        Path file = requireInside(configFile, true);
        if (Files.size(file) > 256 * 1024) throw new IOException("MCP config exceeds 256 KiB");
        McpClientConfig config = MAPPER.readValue(Files.readAllBytes(file), McpClientConfig.class);
        return new McpClientConfig(config.servers().stream().map(this::validated).toList());
    }

    private McpServerConfig validated(McpServerConfig server) {
        try {
            Path executable = userConfiguration ? McpArtifactSnapshot.requirePlain(server.executable()) : requireInside(server.executable(), true);
            Path working = userConfiguration ? McpArtifactSnapshot.requirePlain(server.workingDirectory()) : requireInside(server.workingDirectory(), false);
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MCP executable is not a regular file");
            if (!Files.isDirectory(working, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MCP working directory is not a directory");
            if (userConfiguration && java.util.Set.of("node", "node.exe").contains(executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) {
                if (server.args().isEmpty()) throw new IOException("Node requires a fixed script entry");
                Path entry = McpArtifactSnapshot.requirePlain(Path.of(server.args().getFirst()));
                if (!Files.isRegularFile(entry) || !entry.toString().matches("(?i).+\\.(?:js|mjs|cjs)$"))
                    throw new IOException("Node requires an installed JavaScript entry");
                Path modules = entry.getParent();
                while (modules != null && (modules.getFileName()==null || !modules.getFileName().toString().equals("node_modules"))) modules=modules.getParent();
                if (modules == null) throw new IOException("Node package entry must be inside a fixed node_modules installation");
                Path lock = McpArtifactSnapshot.requirePlain(modules.getParent().resolve("package-lock.json"));
                if (!Files.isRegularFile(lock) || !covered(server, modules) || !covered(server, lock))
                    throw new IOException("artifact_paths must cover the complete node_modules directory and package-lock.json");
            }
            return new McpServerConfig(server.serverId(), server.enabled(), server.transport(),
                    executable, server.args(), working, server.environmentReferences(),
                    server.limits(), server.allowedToolNames(), server.supportedProtocolVersions(), server.artifactPaths());
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid trusted MCP path", e);
        }
    }

    private static boolean covered(McpServerConfig server, Path target) throws IOException {
        for (Path artifact : server.artifactPaths()) {
            Path root=McpArtifactSnapshot.requirePlain(artifact);
            if (Files.isDirectory(root) ? target.startsWith(root) : target.equals(root)) return true;
        }
        return false;
    }

    private Path requireInside(Path input, boolean file) throws IOException {
        if (input == null) throw new IOException(file ? "MCP file path is required" : "MCP directory path is required");
        Path normalized = input.isAbsolute() ? input.normalize() : trustedRoot.resolve(input).normalize();
        if (!normalized.startsWith(trustedRoot)) throw new IOException("MCP path escapes trusted configuration root");
        if (userConfiguration) return McpArtifactSnapshot.requirePlain(normalized);
        Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(trustedRoot) || Files.isSymbolicLink(normalized)) throw new IOException("MCP symlink or junction path is not trusted");
        return real;
    }
}
