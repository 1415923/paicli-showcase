package com.paicli.interactive.runtime;

import com.paicli.codeindex.JavaSourceDiscovery;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveCodeIntelligenceStageTest {
    @TempDir
    Path temp;

    @Test
    void zeroCandidatesDoNotInvokeJavaSourceDiscovery() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("plain-task"));
        CountingDiscovery discovery = new CountingDiscovery();

        var facts = new InteractiveCodeIntelligenceStage(discovery).inspect(
                workspace, "列出当前目录并说明文件用途", List.of(), null);

        assertEquals(0, discovery.calls);
        assertEquals(0, facts.indexedJavaFiles());
        assertEquals(0, facts.queryCount());
        assertEquals(0, facts.contextItemCount());
    }

    @Test
    void explicitJavaReferenceScansOnlyControlledWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("controlled-workspace"));
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public class Sample {}\n");
        Path outside = temp.resolve("outside/Outside.java");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "public class Outside {}\n");
        CountingDiscovery discovery = new CountingDiscovery();

        try (StateStore state = new StateStore(workspace)) {
            var facts = new InteractiveCodeIntelligenceStage(discovery).inspect(
                    workspace, "inspect referenced source",
                    List.of("src/main/java/demo/Sample.java"), state);

            assertEquals(1, discovery.calls);
            assertEquals(workspace.toRealPath(), discovery.lastRoot.toRealPath());
            assertEquals(1, facts.indexedJavaFiles());
            assertTrue(facts.relativeFiles().contains("src/main/java/demo/Sample.java"));
            assertFalse(facts.relativeFiles().stream().anyMatch(path ->
                    path.contains("Outside.java")));
        }
    }

    private static final class CountingDiscovery extends JavaSourceDiscovery {
        private int calls;
        private Path lastRoot;

        @Override
        public DiscoveryResult discover(Path workspaceRoot,
                ProgressListener progressListener, CancellationProbe cancellationProbe) {
            calls++;
            lastRoot = workspaceRoot;
            return super.discover(workspaceRoot, progressListener, cancellationProbe);
        }
    }
}
