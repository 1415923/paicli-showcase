package com.paicli.interactive.query;

import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.shell.InteractivePromptRenderer;
import com.paicli.interactive.task.InteractiveTaskCommandService;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.state.DatabaseManager;
import com.paicli.state.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class R2ReadSurfaceSideEffectTest {
    @TempDir Path workspace;

    @Test void promptStatusPlanTasksSessionsProviderAndReportPerformNoBusinessWrites()
            throws Exception {
        Path stateDirectory = workspace.resolve(".paicli").resolve("state");
        try (DatabaseManager database = new DatabaseManager(stateDirectory)) {
            new MigrationRunner(database.getConnection()).runMigrations();
        }
        Map<String, Long> before = rowCounts(stateDirectory);
        InteractiveSession session = new InteractiveSession("session-r2-surfaces", workspace,
                "ollama", "model", "on-request",
                InteractiveSession.TerminalMode.PLAIN,
                Instant.parse("2026-07-20T00:00:00Z"));
        session.start();
        StateStoreInteractiveQueryFacade queries = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView());

        assertFalse(new InteractivePromptRenderer(queries)
                .primary(session.snapshot()).isBlank());
        assertFalse(queries.status(session.snapshot(), List.of()).isEmpty());
        assertFalse(queries.status(session.snapshot(), List.of("task")).isEmpty());
        assertFalse(queries.status(session.snapshot(), List.of("provider")).isEmpty());
        assertFalse(queries.status(session.snapshot(), List.of("usage")).isEmpty());
        assertFalse(queries.status(session.snapshot(), List.of("--details")).isEmpty());
        assertFalse(queries.plan(session.snapshot()).isEmpty());
        assertFalse(queries.tasks(session.snapshot(),List.of("recent")).isEmpty());
        assertFalse(queries.providerStatus(session.snapshot()).isEmpty());
        assertFalse(queries.report(session.snapshot(), null).isEmpty());
        var sessions = new InteractiveTaskCommandService(workspace, queries,
                TaskSubmissionPort.runtimeNotConnected(), ignored -> null)
                .sessions(session);
        assertNotNull(sessions);
        assertEquals(before, rowCounts(stateDirectory));
    }

    private static Map<String, Long> rowCounts(Path stateDirectory) throws Exception {
        try (DatabaseManager database = new DatabaseManager(stateDirectory)) {
            Connection connection = database.getConnection();
            Map<String, Long> result = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet tables = statement.executeQuery("""
                         SELECT name FROM sqlite_master
                         WHERE type='table' AND name NOT LIKE 'sqlite_%'
                         ORDER BY name
                         """)) {
                while (tables.next()) {
                    String name = tables.getString(1);
                    try (Statement count = connection.createStatement();
                         ResultSet row = count.executeQuery(
                                 "SELECT count(*) FROM \"" + name + "\"")) {
                        assertTrue(row.next());
                        result.put(name, row.getLong(1));
                    }
                }
            }
            return Map.copyOf(result);
        }
    }
}
