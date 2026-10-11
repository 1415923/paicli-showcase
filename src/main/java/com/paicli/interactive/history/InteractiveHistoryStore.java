package com.paicli.interactive.history;

import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.runtime.PersistenceSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class InteractiveHistoryStore {
    public static final int MAX_ENTRIES = 1000;
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|auth(?:orization)?|bearer|password|passwd|secret|credential|private[_-]?key)\\s*[:= ]");
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[A-Z_][A-Z0-9_]{1,63}=.+$");
    private static final Pattern TOKEN_LIKE = Pattern.compile(
            "(?i).*(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}).*");
    private static final PersistenceSanitizer PERSISTENCE_SANITIZER = new PersistenceSanitizer();

    private final Path historyRoot;
    private final Path historyFile;
    private final int maxEntries;
    private final int maxFileBytes;
    private boolean enabled = true;
    private String warning;

    public InteractiveHistoryStore(Path historyRoot) { this(historyRoot, MAX_ENTRIES, MAX_FILE_BYTES); }

    InteractiveHistoryStore(Path historyRoot, int maxEntries, int maxFileBytes) {
        if (historyRoot == null || maxEntries < 1 || maxFileBytes < 128) {
            throw new IllegalArgumentException("invalid history configuration");
        }
        this.historyRoot = historyRoot.toAbsolutePath().normalize();
        this.historyFile = this.historyRoot.resolve("interactive-history");
        this.maxEntries = maxEntries; this.maxFileBytes = maxFileBytes;
    }

    public static InteractiveHistoryStore defaultStore() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return disabled("user home unavailable");
        return new InteractiveHistoryStore(Path.of(home, ".paicli", "history"));
    }

    public synchronized List<String> load() {
        if (!enabled || !Files.exists(historyFile, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            verifyNoLinks();
            if (Files.size(historyFile) > maxFileBytes) throw new IOException("history exceeds size limit");
            List<String> result = new ArrayList<>();
            for (String encoded : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                String line = decode(encoded);
                if (acceptable(line) && (result.isEmpty() || !result.getLast().equals(line))) result.add(line);
            }
            if (result.size() > maxEntries) result = new ArrayList<>(result.subList(result.size() - maxEntries, result.size()));
            return List.copyOf(result);
        } catch (IOException | RuntimeException error) {
            disable("history read failed"); return List.of();
        }
    }

    public synchronized boolean append(String input) {
        if (!enabled || !acceptable(input)) return false;
        try {
            List<String> entries = new ArrayList<>(load());
            if (!enabled || (!entries.isEmpty() && entries.getLast().equals(input))) return false;
            entries.add(input);
            while (entries.size() > maxEntries || encodedSize(entries) > maxFileBytes) entries.removeFirst();
            verifyNoLinks();
            Files.createDirectories(historyRoot);
            securePermissions(historyRoot, true);
            verifyNoLinks();
            Path staging = Files.createTempFile(historyRoot, "history-", ".tmp");
            try {
                StringBuilder payload = new StringBuilder();
                for (String entry : entries) payload.append(encode(entry)).append('\n');
                Files.writeString(staging, payload, StandardCharsets.UTF_8);
                securePermissions(staging, false);
                try {
                    Files.move(staging, historyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(staging, historyFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(staging); }
            return true;
        } catch (IOException | RuntimeException error) {
            disable("history write failed"); return false;
        }
    }

    public synchronized List<String> recent(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("history limit must be 1..100");
        List<String> entries = load();
        int start = Math.max(0, entries.size() - limit);
        List<String> newestFirst = new ArrayList<>(entries.subList(start, entries.size()));
        java.util.Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    public synchronized boolean clear() {
        if (!enabled) return false;
        try {
            verifyNoLinks();
            Files.deleteIfExists(historyFile);
            return true;
        } catch (IOException | RuntimeException error) {
            disable("history clear failed");
            return false;
        }
    }

    public synchronized boolean enabled() { return enabled; }
    public synchronized String warning() { return warning; }
    Path historyFile() { return historyFile; }

    private void verifyNoLinks() throws IOException {
        Path parent = historyRoot.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) rejectLink(parent);
        if (Files.exists(historyRoot, LinkOption.NOFOLLOW_LINKS)) rejectLink(historyRoot);
        if (Files.exists(historyFile, LinkOption.NOFOLLOW_LINKS)) rejectLink(historyFile);
    }

    private static void rejectLink(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("history path is a link");
    }

    private static void securePermissions(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs are inherited from the user-owned control directory.
        }
    }

    private static InteractiveHistoryStore disabled(String warning) {
        InteractiveHistoryStore store = new InteractiveHistoryStore(Path.of("."));
        store.disable(warning); return store;
    }
    private void disable(String value) { enabled = false; warning = value; }

    private static boolean acceptable(String input) {
        if (input == null || input.isBlank() || input.length() > SlashCommandParser.MAX_INPUT_CHARS
                || input.indexOf('\r') >= 0) return false;
        String lower = input.toLowerCase(Locale.ROOT);
        String canonicalSafe = PERSISTENCE_SANITIZER.sanitize(input);
        boolean sensitiveLabel = SENSITIVE.matcher(input).find();
        boolean explicitlyRedacted = input.contains("<redacted>")
                || input.contains("[redacted complete HTTP message]");
        return input.equals(canonicalSafe) && (!sensitiveLabel || explicitlyRedacted)
                && !ENV_ASSIGNMENT.matcher(input).matches()
                && !TOKEN_LIKE.matcher(input).matches()
                && !lower.startsWith("/provider credential") && !lower.startsWith("/provider key");
    }
    private static String encode(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n"); }
    private static String decode(String value) {
        StringBuilder result = new StringBuilder(); boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) { result.append(current == 'n' ? '\n' : current); escaped = false; }
            else if (current == '\\') escaped = true; else result.append(current);
        }
        if (escaped) result.append('\\');
        return result.toString();
    }
    private static int encodedSize(List<String> entries) {
        int size = 0;
        for (String entry : entries) size += encode(entry).getBytes(StandardCharsets.UTF_8).length + 1;
        return size;
    }
}
