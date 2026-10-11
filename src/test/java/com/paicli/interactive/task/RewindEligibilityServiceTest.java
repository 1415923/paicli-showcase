package com.paicli.interactive.task;

import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.InteractiveProposalStore;
import com.paicli.state.RewindService;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class RewindEligibilityServiceTest {
    @TempDir Path temp;

    @Test
    void uniquelyLinksLegacySnapshotAndSuccessfulWriteThenRechecksDrift() throws Exception {
        Path workspace = prepareWorkspace("return 1");
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        String snapshotId;
        try (StateStore state = legacyApplied(workspace, source, true, false)) {
            snapshotId = state.snapshots.findByTask("task-legacy").getFirst().snapshotId();
            RewindEligibilityService service = new RewindEligibilityService(workspace);
            RewindEligibilityService.Eligibility eligible = service.evaluate(state, snapshotId);
            assertEquals(RewindEligibilityService.REWINDABLE, eligible.code());
            assertTrue(eligible.legacy());
            assertEquals("run-legacy", eligible.runId());
            var selector = new StateStoreInteractiveQueryFacade(
                    workspace, new InteractiveRuntimeView()).snapshotView(snapshotId)
                    .orElseThrow();
            assertTrue(selector.rewindable());
            assertEquals(RewindEligibilityService.REWINDABLE,
                    selector.eligibilityCode());

            Files.writeString(source, "package demo; class Sample { int value() { return 3; } }\n");
            assertEquals(RewindEligibilityService.CURRENT_HASH_MISMATCH,
                    service.evaluate(state, snapshotId).code());
        }
    }

    @Test
    void missingSuccessfulWriteOrPathMismatchCannotLinkLegacySnapshot() throws Exception {
        Path noWriteWorkspace = prepareWorkspace("return 1");
        Path noWriteSource = noWriteWorkspace.resolve("src/main/java/demo/Sample.java");
        try (StateStore state = legacyApplied(noWriteWorkspace, noWriteSource, false, false)) {
            String id = state.snapshots.findByTask("task-legacy").getFirst().snapshotId();
            assertEquals(RewindEligibilityService.LEGACY_LINK_UNAVAILABLE,
                    new RewindEligibilityService(noWriteWorkspace).evaluate(state, id).code());
            var selector = new StateStoreInteractiveQueryFacade(
                    noWriteWorkspace, new InteractiveRuntimeView()).snapshotView(id)
                    .orElseThrow();
            assertFalse(selector.rewindable());
            assertEquals(RewindEligibilityService.LEGACY_LINK_UNAVAILABLE,
                    selector.eligibilityCode());
        }

        Path mismatchWorkspace = temp.resolve("mismatch");
        Path mismatchSource = createSource(mismatchWorkspace, "return 1");
        try (StateStore state = legacyApplied(mismatchWorkspace, mismatchSource, true, true)) {
            String id = state.snapshots.findByTask("task-legacy").getFirst().snapshotId();
            assertEquals(RewindEligibilityService.LEGACY_LINK_UNAVAILABLE,
                    new RewindEligibilityService(mismatchWorkspace).evaluate(state, id).code());
        }
    }

    @Test
    void partiallyCompletedLegacyRewindIsReconciledIdempotentlyWithoutAnotherFileWrite()
            throws Exception {
        Path workspace = prepareWorkspace("return 1");
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        try (StateStore state = legacyApplied(workspace, source, true, false)) {
            String id = state.snapshots.findByTask("task-legacy").getFirst().snapshotId();
            RewindEligibilityService service = new RewindEligibilityService(workspace);
            RewindEligibilityService.Eligibility eligibility = service.evaluate(state, id);
            new RewindService(workspace, state.snapshots).rewind(id);
            RewindEligibilityService.Eligibility restored = service.evaluate(state, id);
            assertEquals(RewindEligibilityService.ALREADY_REWOUND, restored.code());
            assertTrue(restored.legacy());
            service.ensureProposalLink(state, restored, InteractiveProposalStore.REWOUND);
            service.ensureProposalLink(state, restored, InteractiveProposalStore.REWOUND);
            assertEquals(1, state.interactiveProposals.findByTask("task-legacy", 10).size());
            assertEquals(InteractiveProposalStore.REWOUND,
                    state.interactiveProposals.findByTask("task-legacy", 10)
                            .getFirst().status());
            assertTrue(Files.readString(source).contains("return 1"));
        }
    }

    private StateStore legacyApplied(Path workspace, Path source, boolean successfulWrite,
            boolean mismatchedPath) throws Exception {
        StateStore state = new StateStore(workspace);
        state.tasks.createTask("task-legacy", "legacy edit", "ollama", "model",
                "on-request", 4);
        state.tasks.markRunning("task-legacy");
        state.codingRuns.create("run-legacy", "task-legacy", CodingRunStore.KIND_INITIAL,
                null, 1, 0);
        FileSnapshotService snapshots = new FileSnapshotService(workspace, state.snapshots);
        snapshots.captureBeforeWrite("task-legacy", null,
                Path.of("src/main/java/demo/Sample.java"));
        String changed = "package demo; class Sample { int value() { return 2; } }\n";
        Files.writeString(source, changed);
        String id = state.snapshots.findByTask("task-legacy").getFirst().snapshotId();
        snapshots.markAppliedAfterWrite(id, Path.of("src/main/java/demo/Sample.java"));
        if (successfulWrite) {
            String path = mismatchedPath ? "src/main/java/other/Sample.java"
                    : "src/main/java/demo/Sample.java";
            state.toolCalls.record("task-legacy", 1, null, "write_file",
                    "{\"path\":\"" + path + "\",\"content\":\"<redacted sha256="
                            + sha256(changed) + " bytes="
                            + changed.getBytes(StandardCharsets.UTF_8).length
                            + ">\",\"overwrite\":true}",
                    ToolResult.success("written"));
        }
        state.codingRuns.updateLastIteration("run-legacy", 1);
        return state;
    }

    private Path prepareWorkspace(String ignored) throws Exception {
        Path workspace = temp.resolve("workspace-" + java.util.UUID.randomUUID());
        createSource(workspace, ignored);
        return workspace;
    }

    private Path createSource(Path workspace, String ignored) throws Exception {
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,
                "package demo; class Sample { int value() { return 1; } }\n");
        return source;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
