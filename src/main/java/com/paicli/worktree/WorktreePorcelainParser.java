package com.paicli.worktree;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Strict parser for {@code git worktree list --porcelain -z}. */
public final class WorktreePorcelainParser {

    public List<Entry> parse(String output) throws ManagedWorktreeException {
        if (output == null) {
            throw malformed("porcelain output is null");
        }
        String[] fields = output.split("\\u0000", -1);
        List<Entry> entries = new ArrayList<>();
        Builder current = null;
        for (String raw : fields) {
            String field = stripRecordNewline(raw);
            if (field.isEmpty()) {
                if (current != null) {
                    entries.add(current.build());
                    current = null;
                }
                continue;
            }
            if (field.startsWith("worktree ")) {
                if (current != null) entries.add(current.build());
                current = new Builder(path(field.substring(9)));
            } else {
                if (current == null) throw malformed("field before worktree path");
                current.accept(field);
            }
        }
        if (current != null) entries.add(current.build());
        return List.copyOf(entries);
    }

    private String stripRecordNewline(String value) {
        int offset = 0;
        while (offset < value.length() && (value.charAt(offset) == '\n'
                || value.charAt(offset) == '\r')) offset++;
        return value.substring(offset);
    }

    private Path path(String value) throws ManagedWorktreeException {
        if (value.isBlank()) throw malformed("worktree path is blank");
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ManagedWorktreeException(
                    ManagedWorktreeException.Code.RECONCILIATION_MISMATCH,
                    "Malformed worktree path", e);
        }
    }

    private ManagedWorktreeException malformed(String message) {
        return new ManagedWorktreeException(
                ManagedWorktreeException.Code.RECONCILIATION_MISMATCH,
                "Malformed Git worktree porcelain: " + message);
    }

    public record Entry(Path path, String head, String branch, boolean detached,
                        boolean bare, boolean locked, String lockReason,
                        boolean prunable, String pruneReason) { }

    private final class Builder {
        private final Path path;
        private String head;
        private String branch;
        private boolean detached;
        private boolean bare;
        private boolean locked;
        private String lockReason;
        private boolean prunable;
        private String pruneReason;

        private Builder(Path path) { this.path = path; }

        private void accept(String field) throws ManagedWorktreeException {
            if (field.startsWith("HEAD ")) head = unique(head, field.substring(5), "HEAD");
            else if (field.startsWith("branch ")) branch = unique(branch, field.substring(7), "branch");
            else if (field.equals("detached")) detached = true;
            else if (field.equals("bare")) bare = true;
            else if (field.equals("locked")) locked = true;
            else if (field.startsWith("locked ")) {
                locked = true;
                lockReason = field.substring(7);
            } else if (field.equals("prunable")) prunable = true;
            else if (field.startsWith("prunable ")) {
                prunable = true;
                pruneReason = field.substring(9);
            } else throw malformed("unknown field");
        }

        private String unique(String existing, String value, String name)
                throws ManagedWorktreeException {
            if (existing != null || value.isBlank()) throw malformed("invalid " + name);
            return value;
        }

        private Entry build() throws ManagedWorktreeException {
            if (!bare && (head == null || head.isBlank())) throw malformed("missing HEAD");
            if (branch != null && detached) throw malformed("branch and detached both present");
            return new Entry(path, head, branch, detached, bare, locked, lockReason,
                    prunable, pruneReason);
        }
    }
}
