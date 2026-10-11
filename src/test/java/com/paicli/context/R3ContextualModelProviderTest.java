package com.paicli.context;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paicli.context.ContextModels.*;
import com.paicli.config.Config;
import com.paicli.interactive.render.InteractiveViewModelRenderer;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class R3ContextualModelProviderTest {
    @TempDir Path workspace;

    @Test void providerTurnSnapshotAndRetryAdmissionStayBound()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            AgentMessage goal=AgentMessage.userGoal("inspect safely");
            admitted.guard().appendEvent(0,goal);
            AtomicReference<ProviderContextRuntime.ProviderTurnIdentity> observed=
                    new AtomicReference<>();
            com.paicli.provider.ModelProvider delegate=(messages,tools)->{
                observed.set(ProviderContextRuntime.currentTurn());
                ProviderContextRuntime.requireRetryAdmission();
                assertSame(observed.get(),ProviderContextRuntime.currentTurn());
                return ModelResponse.finalAnswer("done");
            };
            var provider=new ContextualModelProvider(delegate,state,workspace,root.taskId().value(),
                    "mock","model",OperationKind.CHAT,new CapabilityProfile("reported",131072,
                    LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,"system");
            assertEquals("done",provider.chat(OperationKind.CHAT,"logical-turn-1",
                    List.of(goal),List.of(),admitted.guard()).content());
            var identity=observed.get();assertNotNull(identity);
            assertEquals(root.taskId().value(),identity.taskId());
            assertEquals(root.initialRun().id().value(),identity.runId());
            assertEquals(64,identity.sourceSnapshotHash().length());
            assertEquals(64,identity.compiledSnapshotHash().length());
            assertNull(ProviderContextRuntime.currentTurn());
            try(var query=new com.paicli.state.DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=query.getConnection().prepareStatement(
                        "SELECT count(*) FROM side_effect_occurrences WHERE occurrence_id=?")){
                statement.setString(1,identity.providerOccurrenceId());
                try(var row=statement.executeQuery()){assertTrue(row.next());assertEquals(1,row.getInt(1));}
            }
        }
    }

    @Test void explicitInvocationAndDurableSourcesPrecedeProviderWhileBudgetsFailClosed()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            AtomicInteger calls=new AtomicInteger();AtomicReference<List<AgentMessage>> sent=new AtomicReference<>();
            AtomicReference<List<ToolDefinition>> sentTools=new AtomicReference<>();
            com.paicli.provider.ModelProvider delegate=(messages,tools)->{
                calls.incrementAndGet();sent.set(List.copyOf(messages));sentTools.set(List.copyOf(tools));
                return ModelResponse.finalAnswer("ok");};
            var known=new ContextualModelProvider(delegate,state,workspace,root.taskId().value(),
                    "mock","model",OperationKind.CHAT,new CapabilityProfile("reported",131072,
                    LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,"system");
            String secret="R3_SECRET_742913";
            AgentMessage goal=AgentMessage.userGoal("Authorization: Bearer "+secret);admitted.guard().appendEvent(0,goal);
            Logger rootLogger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();rootLogger.addAppender(logs);
            assertEquals("CONTEXT_INVOCATION_REQUIRED",assertThrows(
                    ContextualModelProvider.ContextGenerationException.class,
                    ()->known.chat(List.of(goal),List.of())).getMessage());
            assertEquals("ok",known.chat(OperationKind.CHAT,"1",List.of(goal),List.of(),
                    admitted.guard()).content());
            assertEquals(1,calls.get());
            assertTrue(sent.get().stream().noneMatch(message->message.content().contains(secret)));
            var pointer=state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.CHAT).orElseThrow();
            var generation=state.contexts.find(pointer.contextGenerationId()).orElseThrow();
            assertEquals(Lifecycle.READY,generation.lifecycle());
            String recomputed=DeterministicContextCompiler.hash(
                    DeterministicContextCompiler.segmentSetHash(state.contexts.segments(pointer.contextGenerationId()))
                            +"|"+DeterministicContextCompiler.compiledHash(sent.get(),sentTools.get()));
            assertEquals(generation.compiledHash(),recomputed,
                    "the canonical hash closes over the exact sanitized Provider material");
            assertTrue(state.contexts.references(pointer.contextGenerationId()).stream()
                    .noneMatch(reference->reference.type().startsWith("MESSAGE_")));
            try(var query=new com.paicli.state.DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=query.getConnection().prepareStatement("""
                  SELECT (SELECT count(*) FROM context_generations
                           WHERE provider LIKE ? OR model LIKE ? OR capability_profile LIKE ?
                              OR failure_code LIKE ? OR preview_summary LIKE ?)
                       + (SELECT count(*) FROM context_segments WHERE sanitized_payload LIKE ?)
                       + (SELECT count(*) FROM context_source_references
                          WHERE source_id LIKE ? OR source_revision LIKE ?)
                       + (SELECT count(*) FROM context_generation_pointers
                          WHERE task_id LIKE ? OR run_id LIKE ? OR active_context_generation_id LIKE ?)
                       + (SELECT count(*) FROM provider_context_telemetry_links
                          WHERE CAST(telemetry_id AS TEXT) LIKE ? OR context_generation_id LIKE ?)
                  """)){
                for(int i=1;i<=13;i++)statement.setString(i,"%"+secret+"%");
                try(var row=statement.executeQuery()){
                    assertTrue(row.next());assertEquals(0,row.getInt(1));
                }
            }
            String renderedStatus=String.join("\n",new com.paicli.state.ContextReadOnlyStatusQuery(workspace)
                    .read(root.taskId().value()).lines());
            assertFalse(renderedStatus.contains(secret));
            InteractiveSession session=new InteractiveSession("r3-privacy",workspace,"mock","model",
                    Config.MODE_DENY,InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();session.startTask(root.taskId().value(),root.initialRun().id().value());
            String renderedView=String.join("\n",new InteractiveViewModelRenderer().status(
                    state.viewModels.read(session.snapshot(),null),List.of()));
            assertFalse(renderedView.contains(secret));

            var unknown=new ContextualModelProvider(delegate,state,workspace,root.taskId().value(),
                    "compatible","model",OperationKind.PLAN,new CapabilityProfile("unknown",null,
                    LimitProvenance.UNKNOWN_LIMIT,128,32),false,"system");
            var unknownFailure=assertThrows(ContextualModelProvider.ContextGenerationException.class,()->
                    unknown.chat(OperationKind.PLAN,"plan:1",List.of(AgentMessage.userGoal("plan")),
                            List.of(),admitted.guard()));
            assertEquals("CONTEXT_BUDGET_UNKNOWN",unknownFailure.getMessage());
            assertFalse(unknownFailure.getMessage().contains(secret));
            assertEquals(1,calls.get());
            assertTrue(state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.PLAN).isEmpty());

            var conversation=new ArrayList<AgentMessage>();conversation.add(goal);
            AgentMessage irreducible=AgentMessage.userGoal("u".repeat(12000));
            admitted.guard().appendEvent(1,irreducible);conversation.add(irreducible);
            for(int i=0;i<7;i++){
                AgentMessage action=AgentMessage.actionWithToolCall("durable tool request "+i,
                        "call-"+i,"{\"id\":\"call-"+i+"\",\"type\":\"function\","
                                +"\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}");
                AgentMessage observation=AgentMessage.observationForToolCall(
                        "durable tool fact "+i+" "+"x".repeat(64),"call-"+i);
                admitted.guard().appendEvent(i+2,action);conversation.add(action);
                var occurrence=admitted.guard().admitOccurrence(i+2,
                        new ToolCall("read_file",Map.of("path","fixture-"+i)));
                admitted.guard().recordToolCall(i+2,"call-"+i,"read_file","{redacted}",
                        ToolResult.success("durable tool fact "+i));
                admitted.guard().completeOccurrence(occurrence,
                        ToolResult.success("durable tool fact "+i));
                admitted.guard().appendEvent(i+2,observation);conversation.add(observation);
            }
            var constrained=new ContextualModelProvider(delegate,state,workspace,root.taskId().value(),
                    "mock","model",OperationKind.TOOL_CONTINUATION,new CapabilityProfile("small",1024,
                    LimitProvenance.CONFIGURED_LIMIT,128,64),false,"system");
            var required=assertThrows(ContextualModelProvider.ContextGenerationException.class,()->
                    constrained.chat(OperationKind.TOOL_CONTINUATION,"tool:9",conversation,List.of(),
                            admitted.guard()));
            assertEquals("CONTEXT_BUDGET_EXCEEDED",required.getMessage());assertEquals(1,calls.get());
            assertTrue(state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.TOOL_CONTINUATION).isEmpty(),
                    "an irreducible over-budget context must fail before pointer publication");
            assertEquals(1,calls.get());
            rootLogger.detachAppender(logs);
            String capturedLogs=logs.list.stream().map(event->{
                String args=event.getArgumentArray()==null?"":java.util.Arrays.toString(event.getArgumentArray());
                String thrown=event.getThrowableProxy()==null?"":event.getThrowableProxy().getMessage();
                return event.getFormattedMessage()+"|"+args+"|"+thrown;
            }).collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(capturedLogs.contains(secret));
        }
    }

    @Test void activeCompactedLedgerPayloadTamperFailsBeforeProviderAndPointerPublish()throws Exception{
        assertActiveLedgerCorruptionFailsClosed("payload");
    }

    @Test void activeCompactedLedgerReferenceTamperFailsBeforeProviderAndPointerPublish()throws Exception{
        assertActiveLedgerCorruptionFailsClosed("hash");
    }

    @Test void activeCompactedLedgerReferenceTransplantFailsBeforeProviderAndPointerPublish()throws Exception{
        assertActiveLedgerCorruptionFailsClosed("transplant");
    }

    private void assertActiveLedgerCorruptionFailsClosed(String corruption)throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"active-ledger-integrity"));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            state.contexts.applyCandidate(candidate.contextId(),admitted.guard().current().snapshot());
            AgentMessage goal=AgentMessage.userGoal("continue after the committed projection");
            admitted.guard().appendEvent(0,goal);
            var pointerBefore=state.contexts.pointer(root.taskId().value(),
                    root.initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            long generationsBefore=countGenerations();
            try(var inspection=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=inspection.getConnection().createStatement()){
                if("payload".equals(corruption)){
                    statement.execute("DROP TRIGGER v26_context_segment_update_immutable");
                    try(var update=inspection.getConnection().prepareStatement("""
                      UPDATE context_segments SET sanitized_payload=sanitized_payload||' tampered'
                      WHERE context_generation_id=? AND segment_kind='COMPACTED_LEDGER'
                      """)){
                        update.setString(1,candidate.contextId());assertTrue(update.executeUpdate()>=1);
                    }
                }else if("hash".equals(corruption)){
                    statement.execute("DROP TRIGGER v26_context_reference_update_immutable");
                    try(var update=inspection.getConnection().prepareStatement("""
                      UPDATE context_source_references SET source_hash=?
                      WHERE rowid=(SELECT rowid FROM context_source_references
                                   WHERE context_generation_id=?
                                   ORDER BY ordinal,source_order LIMIT 1)
                      """)){
                        update.setString(1,"0".repeat(64));update.setString(2,candidate.contextId());
                        assertEquals(1,update.executeUpdate());
                    }
                }else{
                    statement.execute("DROP TRIGGER v26_context_reference_update_immutable");
                    String[] transplant=new String[4];
                    try(var query=inspection.getConnection().prepareStatement("""
                      SELECT r.source_type,r.source_id,r.source_revision,r.source_hash
                      FROM context_source_references r
                      WHERE r.context_generation_id=?
                        AND NOT EXISTS(
                          SELECT 1 FROM context_source_references candidate_ref
                          JOIN context_segments candidate_segment
                            ON candidate_segment.context_generation_id=candidate_ref.context_generation_id
                           AND candidate_segment.ordinal=candidate_ref.ordinal
                          WHERE candidate_ref.context_generation_id=?
                            AND candidate_segment.segment_kind='COMPACTED_LEDGER'
                            AND candidate_ref.source_type=r.source_type
                            AND candidate_ref.source_id=r.source_id
                            AND candidate_ref.source_revision IS r.source_revision
                            AND candidate_ref.source_hash=r.source_hash)
                      ORDER BY r.ordinal,r.source_order LIMIT 1
                      """)){
                        query.setString(1,base.contextGenerationId());
                        query.setString(2,candidate.contextId());
                        try(var row=query.executeQuery()){
                            assertTrue(row.next());for(int i=0;i<4;i++)transplant[i]=row.getString(i+1);
                        }
                    }
                    try(var update=inspection.getConnection().prepareStatement("""
                      UPDATE context_source_references
                      SET source_type=?,source_id=?,source_revision=?,source_hash=?
                      WHERE rowid=(
                        SELECT r.rowid FROM context_source_references r
                        JOIN context_segments s ON s.context_generation_id=r.context_generation_id
                          AND s.ordinal=r.ordinal
                        WHERE r.context_generation_id=? AND s.segment_kind='COMPACTED_LEDGER'
                          AND r.source_type!='SOURCE_SEGMENT'
                        ORDER BY r.source_order LIMIT 1)
                      """)){
                        update.setString(1,transplant[0]);update.setString(2,transplant[1]);
                        update.setString(3,transplant[2]);update.setString(4,transplant[3]);
                        update.setString(5,candidate.contextId());assertEquals(1,update.executeUpdate());
                    }
                }
            }
            AtomicInteger calls=new AtomicInteger();
            com.paicli.provider.ModelProvider delegate=(messages,tools)->{
                calls.incrementAndGet();return ModelResponse.finalAnswer("must not run");};
            var provider=new ContextualModelProvider(delegate,state,workspace,root.taskId().value(),
                    "mock","model",OperationKind.CHAT,new CapabilityProfile("reported",131072,
                    LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,"system");
            var failure=assertThrows(ContextualModelProvider.ContextGenerationException.class,()->
                    provider.chat(OperationKind.CHAT,"corrupt-ledger",List.of(goal),List.of(),
                            admitted.guard()));
            assertEquals("COMPACTION_INTEGRITY",failure.getMessage());
            assertEquals(0,calls.get());
            assertEquals(generationsBefore,countGenerations());
            assertEquals(pointerBefore,state.contexts.pointer(root.taskId().value(),
                    root.initialRun().id().value(),OperationKind.CHAT).orElseThrow());
        }
    }

    private long countGenerations()throws Exception{
        try(var inspection=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=inspection.getConnection().createStatement();
            var row=statement.executeQuery("SELECT count(*) FROM context_generations")){
            assertTrue(row.next());return row.getLong(1);
        }
    }
}
