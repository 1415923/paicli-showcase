package com.paicli.state;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope.ToolCallCardinality;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.TelemetryModelProvider;
import com.paicli.interactive.session.InteractiveSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class R2ProviderCallTelemetryStoreTest {
    @TempDir Path temp;

    @Test void startedCanRemainIncompleteAndTerminalFinalizeIsSingleAssignment()
            throws Exception {
        Path state = migrated("lifecycle");
        ProviderCallTelemetryStore store = new ProviderCallTelemetryStore(state);
        String incomplete = UUID.randomUUID().toString();
        assertTrue(store.recordStarted(start(incomplete, Instant.now()
                .minus(2, ChronoUnit.HOURS))));
        String completed = UUID.randomUUID().toString();
        Instant started = Instant.parse("2026-07-20T00:00:00Z");
        assertTrue(store.recordStarted(start(completed, started)));
        ProviderCallObservation observation = new ProviderCallObservation(
                0, 4, 4, 0, null, null,
                ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE,
                0, ToolCallCardinality.ZERO, "stop", "req-safe", null, false);
        var finish = new ProviderCallTelemetryStore.CallFinish(completed,
                ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                started.plusMillis(12), 12, "FINAL", 0, observation,
                "a".repeat(64), "diag-safe");
        assertTrue(store.finalizeCall(finish));
        assertFalse(store.finalizeCall(finish));
        try (DatabaseManager database = new DatabaseManager(state)) {
            assertEquals(1, scalar(database.getConnection(),
                    "SELECT count(*) FROM provider_call_telemetry WHERE lifecycle='STARTED'"));
            assertEquals(1, scalar(database.getConnection(),
                    "SELECT count(*) FROM provider_call_telemetry WHERE input_tokens=0"));
        }
    }

    @Test void writeFailureCannotChangeProviderReturnOrThrownFailure() throws Exception {
        ProviderCallTelemetryStore missingTable = new ProviderCallTelemetryStore(
                temp.resolve("unmigrated"));
        ModelProvider success = (messages, tools) -> ModelResponse.finalAnswer("ok");
        ModelProvider decorated = new TelemetryModelProvider(success, missingTable,
                "ollama", "model", ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty);
        assertEquals("ok", decorated.chat(List.of(AgentMessage.userGoal("goal")),
                List.of()).content());

        IllegalStateException expected = new IllegalStateException("provider failed");
        ModelProvider failure = (messages, tools) -> { throw expected; };
        ModelProvider failed = new TelemetryModelProvider(failure, missingTable,
                "ollama", "model", ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty);
        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> failed.chat(List.of(AgentMessage.userGoal("goal")), List.of())));
    }

    @Test void rawPromptResponseAndSecretNeverEnterTelemetryRows() throws Exception {
        Path state = migrated("privacy");
        String promptSecret = "Bearer sk-prompt-secret";
        String responseSecret = "raw-response-secret";
        ModelProvider delegate = (messages, tools) ->
                ModelResponse.finalAnswer(responseSecret);
        ModelProvider decorated = new TelemetryModelProvider(delegate,
                new ProviderCallTelemetryStore(state), "openai", "safe-model",
                ProviderCallTelemetryStore.OperationKind.CHAT,
                () -> new ProviderCallContext("Authorization: context-secret",
                        null, null, null));
        assertEquals(responseSecret, decorated.chat(
                List.of(AgentMessage.userGoal(promptSecret)), List.of()).content());
        try (DatabaseManager database = new DatabaseManager(state);
             Statement statement = database.getConnection().createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT * FROM provider_call_telemetry LIMIT 1")) {
            assertTrue(row.next());
            StringBuilder persisted = new StringBuilder();
            for (int index = 1; index <= row.getMetaData().getColumnCount(); index++) {
                persisted.append(row.getString(index)).append('|');
            }
            assertFalse(persisted.toString().contains(promptSecret));
            assertFalse(persisted.toString().contains(responseSecret));
            assertFalse(persisted.toString().contains("Bearer"));
            assertFalse(persisted.toString().contains("context-secret"));
        }
    }

    @Test void cleanupNeverDeletesExpiredStartedRows() throws Exception {
        Path state = migrated("retention");
        ProviderCallTelemetryStore store = new ProviderCallTelemetryStore(state);
        Instant old = Instant.now().minus(31, ChronoUnit.DAYS);
        String expired = UUID.randomUUID().toString();
        assertTrue(store.recordStarted(start(expired, old)));
        String current = UUID.randomUUID().toString();
        Instant now = Instant.now();
        assertTrue(store.recordStarted(start(current, now)));
        assertTrue(store.finalizeCall(new ProviderCallTelemetryStore.CallFinish(current,
                ProviderCallTelemetryStore.Lifecycle.FAILED, now.plusMillis(1), 1,
                "PROVIDER_ERROR", 1,
                ProviderCallObservation.notExposed().withError("PROVIDER_TIMEOUT", false),
                null, "timeout-safe")));
        try (DatabaseManager database = new DatabaseManager(state)) {
            assertEquals(1, scalar(database.getConnection(),
                    "SELECT count(*) FROM provider_call_telemetry WHERE correlation_id='"
                            + expired + "'"));
        }
    }

    @Test void countRetentionDeletesOnlyTerminalRowsAndProtectsOldestStarted()
            throws Exception {
        Path state=migrated("retention-started-protection");
        ProviderCallTelemetryStore store=new ProviderCallTelemetryStore(state,
                new ProviderCallTelemetryStore.RetentionPolicy(30,3,2));
        Instant base=Instant.now().minus(2,ChronoUnit.HOURS);
        String active=UUID.randomUUID().toString();
        assertTrue(store.recordStarted(start(active,base)));
        for(int index=0;index<5;index++){
            String id=UUID.randomUUID().toString();
            Instant at=base.plusSeconds(index+1);
            assertTrue(store.recordStarted(start(id,at)));
            assertTrue(store.finalizeCall(new ProviderCallTelemetryStore.CallFinish(id,
                    ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,at.plusMillis(1),1,
                    "FINAL",0,ProviderCallObservation.notExposed(),null,"retention-safe")));
        }
        try(DatabaseManager database=new DatabaseManager(state)){
            assertEquals(1,scalar(database.getConnection(),
                    "SELECT count(*) FROM provider_call_telemetry WHERE correlation_id='"
                            +active+"' AND lifecycle='STARTED'"));
            assertTrue(scalar(database.getConnection(),
                    "SELECT count(*) FROM provider_call_telemetry")<=3);
        }
    }

    @Test void sessionUsageIsAggregatedFromCallRowsWithoutSummaryAuthority()
            throws Exception {
        Path workspace = temp.resolve("aggregate-workspace");
        try (StateStore state = new StateStore(workspace)) {
            String correlation = UUID.randomUUID().toString();
            Instant started = Instant.now();
            assertTrue(state.providerTelemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(
                    correlation, "ollama", "model",
                    ProviderCallTelemetryStore.OperationKind.CHAT,
                    new ProviderCallContext("session-aggregate", null, null, null), started)));
            ProviderCallObservation usage = new ProviderCallObservation(0, 4, 4,
                    null, null, null,
                    ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                    ProviderCallObservation.UsageCoverage.COMPLETE, 0,
                    ToolCallCardinality.ZERO, "stop", null, null, false);
            assertTrue(state.providerTelemetry.finalizeCall(
                    new ProviderCallTelemetryStore.CallFinish(correlation,
                            ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                            started.plusMillis(2), 2, "FINAL", 0, usage,
                            "b".repeat(64), "aggregate-safe")));
            InteractiveSession session = new InteractiveSession("session-aggregate", workspace,
                    "ollama", "model", "on-request",
                    InteractiveSession.TerminalMode.PLAIN, started);
            session.start();
            var provider = state.viewModels.read(session.snapshot(), null).provider();
            assertEquals(1, provider.sessionCallCount());
            assertEquals(0L, provider.sessionInputTokens());
            assertEquals(4L, provider.sessionOutputTokens());
            assertEquals(4L, provider.sessionTotalTokens());
        }
    }

    @Test void interruptedStartedCallIsDisplayedAsStaleIncompleteNotFailed()
            throws Exception {
        Path workspace=temp.resolve("stale-started-workspace");
        Instant started=Instant.now().minus(2,ChronoUnit.HOURS);
        try(StateStore state=new StateStore(workspace)){
            assertTrue(state.providerTelemetry.recordStarted(
                    new ProviderCallTelemetryStore.CallStart(UUID.randomUUID().toString(),
                            "ollama","model",ProviderCallTelemetryStore.OperationKind.CHAT,
                            new ProviderCallContext("session-stale",null,null,null),started)));
            InteractiveSession session=new InteractiveSession("session-stale",workspace,
                    "ollama","model","on-request",
                    InteractiveSession.TerminalMode.PLAIN,started);
            session.start();
            var model=state.viewModels.read(session.snapshot(),null);
            assertEquals(com.paicli.interactive.view.ProviderView.LastCallState
                            .LAST_CALL_INCOMPLETE,
                    model.provider().lastCallState());
            assertTrue(model.warnings().contains("TELEMETRY_STALE"));
            assertNotEquals(com.paicli.interactive.view.OperationalStatusView.Status.FAILED,
                    model.operationalStatus().primary());
        }
    }

    private Path migrated(String name) throws Exception {
        Path state = temp.resolve(name);
        try (DatabaseManager database = new DatabaseManager(state)) {
            new MigrationRunner(database.getConnection()).runMigrations();
        }
        return state;
    }
    private static ProviderCallTelemetryStore.CallStart start(String correlation,
            Instant at) {
        return new ProviderCallTelemetryStore.CallStart(correlation, "ollama", "model",
                ProviderCallTelemetryStore.OperationKind.CHAT,
                new ProviderCallContext("session-safe", null, null, null), at);
    }
    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next()); return row.getInt(1);
        }
    }
}
