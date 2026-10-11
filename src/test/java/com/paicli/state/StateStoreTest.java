package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private ProjectMemory memory;
    private Path memoryDir;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(tempDir.resolve("manual").resolve(".paicli").resolve("state"));
        Connection connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        memoryDir = tempDir.resolve("manual").resolve(".paicli").resolve("memory");
        memory = new ProjectMemory(connection, memoryDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void projectMemoryAppendsAndFindsRecentNotes() throws Exception {
        memory.append("architecture", "Use SQLite as the primary state store.");
        memory.append("task-001", "decision", "Keep JSONL traces alongside SQLite.");
        memory.append("testing", "Close DatabaseManager in Windows tests.");

        List<ProjectMemory.MemoryNote> recent = memory.findRecent(2);

        assertEquals(2, recent.size());
        assertEquals("decision", recent.getFirst().category());
        assertEquals("task-001", recent.getFirst().taskId());
        assertEquals("testing", recent.getLast().category());
        assertEquals(1, memory.findRecent("task-001", 10).size());
    }

    @Test
    void projectMemoryBuildsBoundedContext() throws Exception {
        memory.append("architecture", "SQLite is authoritative.");
        memory.append("testing", "Run the full Maven test suite.");

        String context = memory.asContext(80);

        assertTrue(context.length() <= 80);
        assertTrue(context.contains("[testing] Run the full Maven test suite."));
        assertEquals("", memory.asContext(0));
    }

    @Test
    void projectMemorySynchronizesMarkdown() throws Exception {
        memory.append("task-002", "decision", "Markdown is a human-readable mirror.");

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        assertEquals(memoryFile.toAbsolutePath().normalize(), memory.getMemoryFile());
        assertTrue(Files.exists(memoryFile));
        String markdown = Files.readString(memoryFile);
        assertTrue(markdown.contains("# PaiCLI Project Memory"));
        assertTrue(markdown.contains("**decision**"));
        assertTrue(markdown.contains("task `task-002`"));
        assertTrue(markdown.contains("human-readable mirror"));
    }

    @Test
    void projectMemoryRejectsUnsafeOrOversizedContent() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> memory.append("security", "apiKey=live-value"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.append("network", "CONNECT private.example:443 HTTP/1.1\r\nCookie: value\r\n\r\n"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.append("large", "x".repeat(65 * 1024)));

        assertTrue(memory.findRecent(10).isEmpty());
        assertFalse(Files.exists(memoryDir.resolve("MEMORY.md")));
    }

    @Test
    void stateStoreInitializesFacadeAndClosesSharedConnection() throws Exception {
        Path workspace = tempDir.resolve("facade-workspace");
        StateStore stateStore = new StateStore(workspace);
        try (stateStore) {
            assertNotNull(stateStore.tasks);
            assertNotNull(stateStore.events);
            assertNotNull(stateStore.toolCalls);
            assertNotNull(stateStore.approvals);
            assertNotNull(stateStore.checkpoints);
            assertNotNull(stateStore.memory);
            assertNotNull(stateStore.snapshots);
            assertNotNull(stateStore.codingRuns);
            assertNotNull(stateStore.plans);
            assertNotNull(stateStore.verifications);
            assertNotNull(stateStore.repairAttempts);
            assertNotNull(stateStore.skillLoads);
            assertNotNull(stateStore.skillSelections);
            assertNotNull(stateStore.hookRuns);
            assertNotNull(stateStore.codeIndex);
            assertNotNull(stateStore.codeSearchAudits);
            assertNotNull(stateStore.subagentRuns);
            assertNotNull(stateStore.structuredVerdicts);
            assertNotNull(stateStore.leadDecisions);
            assertNotNull(stateStore.managedWorktrees);
            assertNotNull(stateStore.workerRuns);
            assertNotNull(stateStore.workerTasks);
            assertNotNull(stateStore.taskClaims);
            assertNotNull(stateStore.workerMailbox);
            assertNotNull(stateStore.workerCandidates);
            assertNotNull(stateStore.workerMergeProposals);
            assertNotNull(stateStore.workerIntegrationDecisions);
            assertNotNull(stateStore.workerIntegrationRuns);
            assertNotNull(stateStore.workerCleanups);
            assertNotNull(stateStore.mcpServerManifests);
            assertNotNull(stateStore.mcpSessions);
            assertNotNull(stateStore.mcpToolCatalogs);
            assertNotNull(stateStore.mcpTools);
            assertNotNull(stateStore.mcpToolCalls);
            assertNotNull(stateStore.coreDemoRuns);
            assertNotNull(stateStore.coreDemoCheckpoints);
            assertTrue(Files.exists(workspace.resolve(".paicli").resolve("state").resolve("paicli.db")));

            stateStore.memory.append("architecture", "StateStore owns the shared connection.");
            assertEquals(1, stateStore.memory.findRecent(10).size());
        }
        assertThrows(SQLException.class, () -> stateStore.tasks.findAll(1));
        assertThrows(SQLException.class, () -> stateStore.codingRuns.findByTask("task-001"));
        assertThrows(SQLException.class, () -> stateStore.skillLoads.findByRun("run-001"));
        assertThrows(SQLException.class, () -> stateStore.hookRuns.findByRun("run-001"));
        assertThrows(SQLException.class, () -> stateStore.codeIndex.findFile("src/main/java/App.java"));
        assertThrows(SQLException.class, () -> stateStore.codeSearchAudits.findRecentAudits(1));
        assertThrows(SQLException.class, () -> stateStore.subagentRuns.findRunsByTask("task-001"));
        assertThrows(SQLException.class, () -> stateStore.structuredVerdicts.findVerdictsByRun("sub-001"));
        assertThrows(SQLException.class, () -> stateStore.leadDecisions.findDecisionsByRun("sub-001"));
        assertThrows(SQLException.class, () -> stateStore.managedWorktrees.findById("wt-001"));
        assertThrows(SQLException.class, () -> stateStore.workerRuns.findById("worker-001"));
        assertThrows(SQLException.class, () -> stateStore.workerTasks.findById("item-001"));
        assertThrows(SQLException.class, () -> stateStore.taskClaims.findById("claim-001"));
        assertThrows(SQLException.class, () -> stateStore.workerMailbox.findByWorkerRun("worker-001"));
        assertThrows(SQLException.class, () -> stateStore.workerCandidates.findById("candidate-001"));
        assertThrows(SQLException.class, () -> stateStore.workerMergeProposals.findById("proposal-001"));
        assertThrows(SQLException.class, () -> stateStore.workerIntegrationDecisions.findByProposal("proposal-001"));
        assertThrows(SQLException.class, () -> stateStore.workerIntegrationRuns.findByProposal("proposal-001"));
        assertThrows(SQLException.class, () -> stateStore.workerCleanups.findById("cleanup-001"));
        assertThrows(SQLException.class, () -> stateStore.mcpServerManifests.findManifestById("manifest-001"));
        assertThrows(SQLException.class, () -> stateStore.mcpSessions.findById("session-001"));
        assertThrows(SQLException.class, () -> stateStore.mcpToolCatalogs.findById("catalog-001"));
        assertThrows(SQLException.class, () -> stateStore.mcpTools.findByExposedName("catalog-001", "mcp.test.tool"));
        assertThrows(SQLException.class, () -> stateStore.mcpToolCalls.findById("call-001"));
        assertThrows(SQLException.class, () -> stateStore.coreDemoRuns.findById("run-00000001"));
        assertThrows(SQLException.class, () -> stateStore.coreDemoCheckpoints.findByRun("run-00000001"));
    }
}
