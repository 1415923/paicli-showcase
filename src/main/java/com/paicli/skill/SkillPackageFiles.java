package com.paicli.skill;

import com.paicli.safety.SafetyPolicy;
import com.paicli.safety.SafetyViolationException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Complete byte identity and no-follow resource access; never executes package contents. */
public final class SkillPackageFiles {
    public static final int MAX_FILES = 2048;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private SkillPackageFiles() { }

    public static Path plain(Path path) throws IOException {
        // Reuse the exact executable/artifact ancestor guard already exercised by MCP.
        try { return com.paicli.mcp.config.McpArtifactSnapshot.requirePlain(path.toAbsolutePath()); }
        catch (IOException error) { throw new IOException("Skill path is missing or traverses a link/reparse point", error); }
    }

    public static Snapshot snapshot(Path directory) throws IOException {
        Path root = plain(directory);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Skill package must be a directory");
        SafetyPolicy policy = new SafetyPolicy(root);
        SortedMap<String,String> hashes = new TreeMap<>();
        SortedSet<String> directories = new TreeSet<>();
        long bytes = 0; int entries = 0;
        try (var walk = Files.walk(root, 32)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (++entries > MAX_FILES * 2) throw new IOException("Skill package has too many entries");
                try { policy.validatePath(file); } catch (SafetyViolationException e) { throw new IOException("Unsafe Skill resource",e); }
                String relative = root.relativize(file).toString().replace('\\','/');
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (!relative.isEmpty()) directories.add(relative);
                    if (root.relativize(file).getNameCount() >= 32) throw new IOException("Skill package is too deep");
                    continue;
                }
                if (hashes.size() >= MAX_FILES) throw new IOException("Skill package exceeds 2048 files");
                byte[] content = read(root, relative, (int)(MAX_BYTES - bytes));
                bytes += content.length;
                hashes.put(relative, hash(content));
            }
        }
        if (!hashes.containsKey("SKILL.md")) throw new IOException("Skill package requires SKILL.md");
        // Map.of iteration order may differ between JVMs; confirmation and recovery need
        // the same byte identity after restarting the CLI.
        var canonical = new TreeMap<String,Object>();
        canonical.put("files",hashes);canonical.put("directories",directories);
        var identity = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(canonical);
        return new Snapshot(root, Collections.unmodifiableSortedMap(hashes),
                Collections.unmodifiableSortedSet(directories), hash(identity), bytes);
    }

    public static byte[] read(Path directory, String relative, int limit) throws IOException {
        if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute())
            throw new IOException("Skill resource must be a relative path");
        Path root = plain(directory);
        SafetyPolicy policy = new SafetyPolicy(root);
        try (var channel = policy.openFileForRead(Path.of(relative))) {
            if (channel.size() > limit) throw new IOException("Skill resource exceeds byte budget");
            var output = new java.io.ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                if (output.size() + buffer.remaining() > limit) throw new IOException("Skill resource exceeds byte budget");
                output.write(buffer.array(), 0, buffer.remaining()); buffer.clear();
            }
            return output.toByteArray();
        } catch (SafetyViolationException e) { throw new IOException("Skill resource escapes its package or traverses a link",e); }
    }

    public static String text(byte[] bytes) throws IOException {
        String result = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (result.indexOf('\0') >= 0) throw new IOException("Skill resource is binary; text access only");
        return result;
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record Snapshot(Path root, SortedMap<String,String> hashes,
                           SortedSet<String> directories, String hash, long bytes) { }
}
