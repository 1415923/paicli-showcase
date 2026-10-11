package com.paicli.mcp.config;

import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Recomputed at connection, admission and dispatch; includes actual installed bytes, not just a lockfile. */
public final class McpArtifactSnapshot {
    private McpArtifactSnapshot() { }
    public static String hash(McpServerConfig config) throws IOException {
        if (config.artifactPaths().isEmpty()) return "none";
        SortedMap<String, String> facts = new TreeMap<>();
        long bytes = 0;
        for (Path configured : config.artifactPaths()) {
            Path root = requirePlain(configured);
            try (var entries = Files.walk(root)) {
                var iterator = entries.iterator();
                while (iterator.hasNext()) {
                    Path file = requirePlain(iterator.next());
                    if (Files.isDirectory(file)) continue;
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("non-regular MCP artifact");
                    if (facts.containsKey(file.toString())) continue;
                    if (facts.size() >= 20000 || Files.size(file) > 128L * 1024 * 1024 - bytes)
                        throw new IOException("MCP artifacts exceed 20000 files / 128 MiB");
                    byte[] content;
                    try (var input = Files.newInputStream(file)) {
                        content = input.readNBytes((int) (128L * 1024 * 1024 - bytes + 1));
                    }
                    bytes += content.length;
                    if (bytes > 128L * 1024 * 1024) throw new IOException("MCP artifact too large");
                    facts.put(file.toString(), McpConfigManifestBuilder.hash(content));
                }
            }
        }
        return McpConfigManifestBuilder.hash(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(facts));
    }
    public static Path requirePlain(Path path) throws IOException {
        if (path == null || !path.isAbsolute()) throw new IOException("MCP path must be absolute");
        Path normalized = path.normalize();
        for (Path current = normalized; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || Files.readAttributes(current,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())
                throw new IOException("MCP path traverses a link or reparse point");
        }
        Path real = normalized.toRealPath();
        if (!real.equals(normalized)) throw new IOException("MCP path identity differs from its real path");
        return real;
    }
}
