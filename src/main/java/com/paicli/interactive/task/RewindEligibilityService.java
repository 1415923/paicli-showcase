package com.paicli.interactive.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.InteractiveProposalStore;
import com.paicli.state.StateStore;
import com.paicli.state.ToolCallStore;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One workspace-scoped eligibility decision shared by rewind selection and execution. */
public final class RewindEligibilityService {
    public static final String REWINDABLE = "REWINDABLE";
    public static final String ALREADY_REWOUND = "ALREADY_REWOUND";
    public static final String CURRENT_HASH_MISMATCH = "CURRENT_HASH_MISMATCH";
    public static final String LEGACY_LINK_UNAVAILABLE = "LEGACY_SNAPSHOT_LINK_UNAVAILABLE";
    public static final String PROPOSAL_LINK_INVALID = "PROPOSAL_LINK_INVALID";
    public static final String SNAPSHOT_NOT_APPLIED = "SNAPSHOT_NOT_APPLIED";
    public static final String SNAPSHOT_PAYLOAD_INVALID = "SNAPSHOT_PAYLOAD_INVALID";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REDACTED_HASH = Pattern.compile(
            "<redacted sha256=([0-9a-fA-F]{64})(?:\\s|>)");
    private final Path workspace;

    public RewindEligibilityService(Path workspace) {
        try {
            this.workspace = workspace.toAbsolutePath().normalize()
                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (Exception error) {
            throw new IllegalArgumentException("workspace identity is unavailable", error);
        }
    }

    public Eligibility evaluate(StateStore state, String snapshotId) throws Exception {
        FileSnapshotStore.FileSnapshotRecord snapshot = state.snapshots.findById(snapshotId)
                .orElse(null);
        if (snapshot == null) return unavailable(null, "SNAPSHOT_NOT_FOUND");
        Path target = resolveTarget(snapshot.filePath());
        if (!snapshotPayloadValid(snapshot)) {
            return unavailable(snapshot, SNAPSHOT_PAYLOAD_INVALID);
        }
        LinkResolution proposal = linkedProposal(state, snapshot);
        if (proposal.invalid()) return unavailable(snapshot, PROPOSAL_LINK_INVALID);
        Link link = proposal.link() == null ? legacyLink(state, snapshot) : proposal.link();
        if (link == null) return unavailable(snapshot, LEGACY_LINK_UNAVAILABLE);

        if (FileSnapshotStore.STATUS_RESTORED.equals(snapshot.status())) {
            boolean restoredHash = restoredHashMatches(snapshot, target);
            return new Eligibility(snapshot, link.runId(), link.legacy(), false,
                    restoredHash, restoredHash ? ALREADY_REWOUND : CURRENT_HASH_MISMATCH);
        }
        if (!FileSnapshotStore.STATUS_APPLIED.equals(snapshot.status())) {
            return unavailable(snapshot, SNAPSHOT_NOT_APPLIED);
        }
        boolean currentHashMatches = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && snapshot.sha256After() != null
                && snapshot.sha256After().equals(sha256(target));
        return new Eligibility(snapshot, link.runId(), link.legacy(), currentHashMatches,
                currentHashMatches, currentHashMatches ? REWINDABLE : CURRENT_HASH_MISMATCH);
    }

    public void ensureProposalLink(StateStore state, Eligibility eligibility,
            String terminalStatus) throws Exception {
        if (!eligibility.legacy()) return;
        FileSnapshotStore.FileSnapshotRecord snapshot = eligibility.snapshot();
        String proposalId = "legacy-" + sha256(snapshot.snapshotId() + "\n"
                + eligibility.runId()).substring(0, 32);
        state.interactiveProposals.recordLegacyLinked(proposalId, snapshot.taskId(),
                eligibility.runId(), portable(snapshot.filePath()), snapshot.sha256After(),
                snapshot.snapshotId(), terminalStatus);
    }

    private LinkResolution linkedProposal(StateStore state,
            FileSnapshotStore.FileSnapshotRecord snapshot) throws Exception {
        List<InteractiveProposalStore.Record> linked = state.interactiveProposals
                .findByTask(snapshot.taskId(), 100).stream()
                .filter(item -> snapshot.snapshotId().equals(item.snapshotId()))
                .toList();
        if (linked.isEmpty()) return new LinkResolution(null, false);
        java.util.ArrayList<InteractiveProposalStore.Record> matches =
                new java.util.ArrayList<>();
        for (InteractiveProposalStore.Record item : linked) {
            CodingRunStore.CodingRunRecord run = state.codingRuns.findById(item.runId())
                    .orElse(null);
            if (portable(snapshot.filePath()).equals(portable(item.relativePath()))
                    && run != null && snapshot.taskId().equals(run.taskId())) {
                matches.add(item);
            }
        }
        return matches.size() == 1 && linked.size() == 1
                ? new LinkResolution(new Link(matches.getFirst().runId(), false), false)
                : new LinkResolution(null, true);
    }

    private Link legacyLink(StateStore state, FileSnapshotStore.FileSnapshotRecord snapshot) {
        try {
            if (snapshot.sha256After() == null || snapshot.createdAt() == null) return null;
            List<ToolCallStore.ToolCallRecord> calls = state.toolCalls
                    .findRecentByTask(snapshot.taskId(), 100)
                    .stream().filter(ToolCallStore.ToolCallRecord::success)
                    .filter(call -> "write_file".equals(call.toolName()))
                    .filter(call -> toolMatches(snapshot, call)).toList();
            if (calls.size() != 1) return null;
            ToolCallStore.ToolCallRecord call = calls.getFirst();
            List<CodingRunStore.CodingRunRecord> runs = state.codingRuns
                    .findRecentByTask(snapshot.taskId(), 100).stream()
                    .filter(run -> call.iteration() > run.startedIteration()
                            && call.iteration() <= run.lastIteration())
                    .toList();
            return runs.size() == 1 ? new Link(runs.getFirst().runId(), true) : null;
        } catch (Exception error) {
            return null;
        }
    }

    private boolean toolMatches(FileSnapshotStore.FileSnapshotRecord snapshot,
            ToolCallStore.ToolCallRecord call) {
        try {
            if (snapshot.toolCallId() != null
                    && !snapshot.toolCallId().equals(call.toolCallId())) return false;
            if (snapshot.toolCallId() == null && call.createdAt().compareTo(snapshot.createdAt()) < 0) {
                return false;
            }
            JsonNode arguments = JSON.readTree(call.argumentsJson());
            if (!portable(snapshot.filePath()).equals(portable(arguments.path("path").asText()))) {
                return false;
            }
            String content = arguments.path("content").asText(null);
            if (content == null) return false;
            Matcher redacted = REDACTED_HASH.matcher(content);
            String contentHash = redacted.find() ? redacted.group(1).toLowerCase()
                    : sha256(content);
            return snapshot.sha256After().equals(contentHash);
        } catch (Exception error) {
            return false;
        }
    }

    private Path resolveTarget(String storedPath) throws Exception {
        Path target = workspace.resolve(portable(storedPath)).normalize();
        if (!target.startsWith(workspace)) throw new IllegalArgumentException("path escape");
        new SafetyPolicy(workspace, 1, 1024).validatePath(target);
        return target;
    }

    private boolean restoredHashMatches(FileSnapshotStore.FileSnapshotRecord snapshot, Path target)
            throws Exception {
        if (!snapshot.originalExists()) return !Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && snapshot.sha256Before() != null
                && snapshot.sha256Before().equals(sha256(target));
    }

    private boolean snapshotPayloadValid(FileSnapshotStore.FileSnapshotRecord snapshot) {
        try {
            if (FileSnapshotStore.OPERATION_CREATE.equals(snapshot.operation())) {
                return !snapshot.originalExists() && snapshot.snapshotPath() == null
                        && snapshot.sha256Before() == null;
            }
            if (!snapshot.originalExists() || snapshot.snapshotPath() == null
                    || snapshot.sha256Before() == null) return false;
            Path root = workspace.resolve(".paicli/snapshots").normalize();
            Path payload = root.resolve(portable(snapshot.snapshotPath())).normalize();
            if (!payload.startsWith(root)
                    || Files.isSymbolicLink(payload)
                    || !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) return false;
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realPayload = payload.toRealPath();
            return realRoot.startsWith(workspace) && realPayload.startsWith(realRoot)
                    && snapshot.sha256Before().equals(sha256(payload));
        } catch (Exception error) {
            return false;
        }
    }

    private static Eligibility unavailable(FileSnapshotStore.FileSnapshotRecord snapshot,
            String code) {
        return new Eligibility(snapshot, null, false, false, false, code);
    }

    private static String portable(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private record Link(String runId, boolean legacy) { }
    private record LinkResolution(Link link, boolean invalid) { }

    public record Eligibility(FileSnapshotStore.FileSnapshotRecord snapshot, String runId,
            boolean legacy, boolean rewindable, boolean currentHashMatches, String code) {
        public boolean alreadyRewound() { return ALREADY_REWOUND.equals(code); }
    }
}
