package com.paicli.interactive.runtime;

import com.paicli.agent.AgentLoopObserver;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventPublisher;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.render.InteractiveRuntimeEventRenderer;
import com.paicli.interactive.approval.InteractiveApprovalCoordinator;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.render.PlainRuntimeEventRenderer;
import com.paicli.interactive.render.RuntimeDisplayPreferences;
import com.paicli.interactive.render.TerminalOutputPort;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.interactive.diff.PreWriteDiff;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.PlanResult;
import com.paicli.runtime.VerificationResult;
import com.paicli.safety.ApprovalDecision;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanCompletionModels;
import com.paicli.state.StructuredPlanModels;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeEventBridgeTest {
    @TempDir Path temp;

    @Test void canonicalApprovedPlanCreatedUsesDurableOneStepProjectionWithoutGoal()
            throws Exception{
        Path workspace=temp.resolve("canonical-plan-projection");
        Files.createDirectories(workspace);
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var revision=approvedSingleStepPlan(state,admitted,"Canonical goal",
                    "Read the requested file");
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher("session-plan",
                    Clock.systemUTC());
            publisher.addSink(events::add);
            InteractiveRuntimeView view=new InteractiveRuntimeView();
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session(workspace),
                    view,state,workspace);

            bridge.planCreated(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),new PlanResult(
                            revision.planRevisionId().value(),
                            admitted.root().initialRun().id().value(),
                            admitted.root().taskId().value(),
                            "Canonical goal\n1. Read the requested file"));

            AgentEvents.PlanCreated event=assertInstanceOf(AgentEvents.PlanCreated.class,
                    events.getFirst());
            assertEquals(1,event.stepCount());
            assertEquals(List.of("Read the requested file"),event.boundedSteps());
            assertFalse(event.boundedSteps().contains("Canonical goal"));
            assertEquals(revision.planRevisionId().value(),event.planRevisionId());
            assertEquals(List.of("Read the requested file"),view.snapshot().planSteps());
        }
    }

    @Test void canonicalPlanReadFailuresNeverFallbackToPlanText()throws Exception{
        assertCanonicalProjectionUnavailable("not-found",false,false);
        assertCanonicalProjectionUnavailable("integrity",true,false);
        assertCanonicalProjectionUnavailable("runtime",true,true);
    }

    @Test void legacyPlanIdRetainsBoundedTextProjection()throws Exception{
        Path workspace=temp.resolve("legacy-plan-projection");
        Files.createDirectories(workspace);
        try(StateStore state=new StateStore(workspace)){
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher("session-legacy",
                    Clock.systemUTC());
            publisher.addSink(events::add);
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session(workspace),
                    new InteractiveRuntimeView(),state,workspace);

            bridge.planCreated("legacy-task","legacy-run",new PlanResult(
                    "plan-legacy","legacy-run","legacy-task",
                    "1. Inspect\n2. Verify"));

            AgentEvents.PlanCreated event=assertInstanceOf(AgentEvents.PlanCreated.class,
                    events.getFirst());
            assertEquals(2,event.stepCount());
            assertEquals(List.of("Inspect","Verify"),event.boundedSteps());
        }
    }

    @Test void batchCancellationPreservesCallIdentityAndPosition()throws Exception{
        Path workspace=temp.resolve("batch-cancel");
        Files.createDirectories(workspace);
        try(StateStore state=new StateStore(workspace)){
            String taskId="task-batch-cancel";
            String runId="run-batch-cancel";
            state.tasks.createTask(taskId,"cancel batch","mock",null,"read-only",3);
            state.tasks.markRunning(taskId);
            state.codingRuns.create(runId,taskId,
                    com.paicli.state.CodingRunStore.KIND_INITIAL,null,1,0);
            InteractiveSession session=session(workspace);
            session.startTask(taskId);
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher(
                    "session-batch-cancel",Clock.systemUTC());
            publisher.addSink(events::add);
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session,
                    new InteractiveRuntimeView(),state,workspace);
            bridge.taskStarted(taskId,runId,"cancel batch");
            events.clear();
            AgentLoopObserver.ToolRequestFact request=new AgentLoopObserver.ToolRequestFact(
                    "read_file","{path=notes.txt}","notes.txt","call-notes","read-only",
                    null,1,2,3);

            bridge.toolCancelled(taskId,1,request,"batch cancellation requested");

            AgentEvents.ToolCancelled cancelled=(AgentEvents.ToolCancelled)events.getFirst();
            assertEquals("call-notes",cancelled.toolCallId());
            assertEquals(2,cancelled.batchIndex());
            assertEquals(3,cancelled.batchSize());
        }
    }

    @Test void providerProgressPublishesOnlyAfterQuietIntervalsAndStops()throws Exception{
        Path workspace=temp.resolve("provider-progress");
        Files.createDirectories(workspace);
        try(StateStore state=new StateStore(workspace)){
            InteractiveSession session=session(workspace);
            session.startTask("task-progress");
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher("session-progress",
                    Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"),ZoneOffset.UTC));
            publisher.addSink(events::add);
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session,
                    new InteractiveRuntimeView(),state,workspace);
            var context=new com.paicli.provider.telemetry.ProviderCallContext(
                    "session-progress","task-progress","run-progress",null);

            bridge.started("correlation-progress",context,"PLAN");
            bridge.elapsed("correlation-progress",context,"PLAN",10);
            assertEquals(1,events.size());
            AgentEvents.ModelMessage first=(AgentEvents.ModelMessage)events.getFirst();
            assertEquals("[Model] Planning · 10s elapsed",first.boundedContent());

            publisher.publish(AgentEventType.PHASE_CHANGED,"task-progress","run-progress",
                    metadata->new AgentEvents.PhaseChanged(metadata,"RUNNING"));
            bridge.elapsed("correlation-progress",context,"PLAN",20);
            assertEquals(2,events.size(),"an intervening transcript event suppresses heartbeat");
            bridge.elapsed("correlation-progress",context,"PLAN",30);
            assertEquals(3,events.size(),"next quiet interval may emit one heartbeat");
            bridge.finished("correlation-progress");
            bridge.elapsed("correlation-progress",context,"PLAN",40);
            assertEquals(3,events.size(),"finished calls must not emit heartbeat");
            assertFalse(events.toString().contains("reasoning"));
        }
    }

    @Test void providerRetryLifecyclePublishesOnlyBoundedPublicMetadata()throws Exception{
        Path workspace=temp.resolve("provider-retry");
        Files.createDirectories(workspace);
        try(StateStore state=new StateStore(workspace)){
            InteractiveSession session=session(workspace);
            session.startTask("task-retry");
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher("session-retry",
                    Clock.systemUTC());
            publisher.addSink(events::add);
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session,
                    new InteractiveRuntimeView(),state,workspace);
            var context=new com.paicli.provider.telemetry.ProviderCallContext(
                    "session-retry","task-retry","run-retry",null);

            String digest="a".repeat(64);
            bridge.retrying(new com.paicli.provider.telemetry.ProviderCallProgressSink
                    .RetryProgress("logical-private-id",context,"CHAT",2,1,
                            "TRANSPORT_TLS_EOF",1000,digest));
            bridge.restored("logical-private-id",context,"CHAT",2);
            bridge.exhausted("logical-private-id",context,"CHAT",3,
                    "PRIVATE_CONTINUATION_INCOMPLETE",digest);

            assertEquals(3,events.size());
            AgentEvents.ModelMessage message=(AgentEvents.ModelMessage)events.getFirst();
            assertEquals("MODEL_PROVIDER_RETRY",message.kind());
            assertEquals("[Model] Provider connection interrupted. "
                            +"Retrying provider request 1/2 in 1000 ms.",
                    message.boundedContent());
            assertEquals("[Model] Provider connection restored.",
                    ((AgentEvents.ModelMessage)events.get(1)).boundedContent());
            assertEquals("[Model] Provider request failed after 3 attempts. "
                            +"Category: provider_protocol_incomplete. "
                            +"Durable task state was preserved.",
                    ((AgentEvents.ModelMessage)events.get(2)).boundedContent());
            String publicProjection=events.toString();
            assertFalse(publicProjection.contains(digest));
            assertFalse(publicProjection.contains("logical-private-id"));
            assertFalse(publicProjection.contains("PRIVATE_CONTINUATION"));
            assertFalse(publicProjection.contains("reasoning_content"));
            assertFalse(publicProjection.contains("Authorization"));

            events.clear();
            bridge.retrying(context,"CHAT",3,
                    "PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE");
            AgentEvents.ModelMessage legacy=(AgentEvents.ModelMessage)events.getFirst();
            assertEquals("[Model] Provider response incomplete. "
                    +"Retrying provider request 2/2.",legacy.boundedContent());
            assertFalse(legacy.boundedContent().contains("retrying once"));

            events.clear();
            bridge.retrying(new com.paicli.provider.telemetry.ProviderCallProgressSink
                    .RetryProgress("logical-private-id",context,"TOOL_CONTINUATION",3,2,
                            "PRIVATE_CONTINUATION_INCOMPLETE",0,digest));
            AgentEvents.ModelMessage protocol=(AgentEvents.ModelMessage)events.getFirst();
            assertEquals("[Model] Provider response incomplete. "
                    +"Retrying provider request 2/2 now.",protocol.boundedContent());
            assertFalse(protocol.toString().contains("connection interrupted"));
        }
    }

    @Test void publishesPersistedRuntimeFactsInStrictOrder() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
        try (StateStore state = new StateStore(workspace)) {
            String taskId = "task-1";
            String runId = "run-1";
            state.tasks.createTask(taskId, "fix A", "mock", null, "workspace-write", 3);
            state.tasks.markRunning(taskId);
            state.codingRuns.create(runId, taskId,
                    com.paicli.state.CodingRunStore.KIND_INITIAL,
                    null, 1, 0);

            InteractiveSession session = session(workspace);
            session.startTask(taskId);
            List<AgentEvent> events = new ArrayList<>();
            AgentEventPublisher publisher = new AgentEventPublisher("session-1",
                    Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC));
            StringWriter transcript = new StringWriter();
            publisher.addSink(event -> { throw new IllegalStateException("sink failure"); });
            publisher.addSink(events::add);
            publisher.addSink(new PlainRuntimeEventRenderer(new PrintWriter(transcript)));
            InteractiveRuntimeView view = new InteractiveRuntimeView();
            RuntimeEventBridge bridge = new RuntimeEventBridge(
                    publisher, session, view, state, workspace);

            bridge.taskStarted(taskId, runId, "fix A");
            bridge.planCreated(taskId, runId,
                    new PlanResult("plan-1", runId, taskId,
                            "1. Inspect A\n2. Update A\n3. Verify"));
            bridge.codeContextSelected(taskId, runId,
                    List.of("src/A.java"), List.of("A"), 1, 1, 1);
            AgentLoopObserver.ToolRequestFact read = new AgentLoopObserver.ToolRequestFact(
                    "read_file", "{\"path\":\"src/A.java\"}", "src/A.java");
            bridge.toolRequested(taskId, 1, read);
            bridge.toolDecision(taskId, 1, read,
                    ApprovalDecision.allow("read_file",
                            ApprovalDecision.RiskLevel.LOW, taskId, "allowed"));
            bridge.toolCompleted(taskId, 1, read,
                    new AgentLoopObserver.ToolCompletionFact(
                            true, null, Map.of("bytesRead", 11L)));
            assertNotNull(view.readHash("src/A.java"));
            assertEquals(64, view.readHash("src/A.java").length());

            String snapshotId = "snapshot-1";
            Path target = workspace.resolve("src/A.java");
            state.snapshots.recordCaptured(snapshotId, taskId, "call-1",
                    "src/A.java", ".paicli/snapshots/a",
                    FileSnapshotStore.OPERATION_OVERWRITE, true, 11, "a".repeat(64));
            state.snapshots.markApplied(snapshotId, "b".repeat(64));
            AgentLoopObserver.ToolRequestFact write = new AgentLoopObserver.ToolRequestFact(
                    "write_file", "{\"path\":\"src/A.java\",\"content\":\"[redacted]\"}",
                    "src/A.java");
            bridge.toolRequested(taskId, 2, write);
            bridge.approvalEvents().proposed("proposal-1", taskId, runId, new PreWriteDiff(
                    "src/A.java", "a".repeat(64), "b".repeat(64), 1, 1,
                    List.of("--- a/src/A.java", "+++ b/src/A.java", "-old", "+new"),
                    false, false, true));
            bridge.toolDecision(taskId, 2, write,
                    ApprovalDecision.allow("write_file",
                            ApprovalDecision.RiskLevel.MEDIUM, taskId, "allowed"));
            bridge.toolCompleted(taskId, 2, write,
                    new AgentLoopObserver.ToolCompletionFact(true, null, Map.of(
                            "bytesWritten", 12L, "additions", 1,
                            "deletions", 1, "diffTruncated", false,
                            "snapshotId", snapshotId)));
            bridge.started("verification-1", runId, taskId, 0, "configured verifier");
            bridge.completed(new VerificationResult("verification-1", runId, taskId,
                    0, "fixed", VerificationStore.STATUS_PASSED, 0, 20,
                    "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0", "",
                    "WORKSPACE_CONTENT_SHA256_V1","a".repeat(64)));
            state.tasks.markDone(taskId);
            CodingTaskResult result = new CodingTaskResult(taskId, runId, "plan-1",
                    TaskStore.STATUS_DONE, VerificationStore.STATUS_PASSED, 0, "done");
            InteractiveTaskOutcome outcome = InteractiveTaskOutcome.classify(result, state);
            state.tasks.markInteractiveTerminal(taskId, outcome.persistedStatus());
            bridge.taskCompleted(result, outcome);

            assertEquals(List.of(
                    AgentEventType.TASK_STARTED,
                    AgentEventType.PLAN_CREATED,
                    AgentEventType.CODE_CONTEXT_SELECTED,
                    AgentEventType.TOOL_REQUESTED,
                    AgentEventType.TOOL_APPROVED,
                    AgentEventType.FILE_READ,
                    AgentEventType.TOOL_COMPLETED,
                    AgentEventType.TOOL_REQUESTED,
                    AgentEventType.DIFF_PROPOSED,
                    AgentEventType.TOOL_APPROVED,
                    AgentEventType.SNAPSHOT_CREATED,
                    AgentEventType.FILE_WRITTEN,
                    AgentEventType.TOOL_COMPLETED,
                    AgentEventType.VERIFICATION_STARTED,
                    AgentEventType.VERIFICATION_PASSED,
                    AgentEventType.TASK_COMPLETED),
                    events.stream().map(event -> event.metadata().eventType()).toList());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L,
                            9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L),
                    events.stream().map(event -> event.metadata().sequence()).toList());
            assertTrue(events.stream().allMatch(event ->
                    taskId.equals(event.metadata().taskId())
                            && runId.equals(event.metadata().runId())));
            assertEquals(16, publisher.sinkFailureCount());
            assertEquals(2, transcript.toString().lines()
                    .filter(line -> line.startsWith("• Ran ")).count());
            assertNull(session.snapshot().currentTaskId());
            assertEquals("COMPLETED_VERIFIED", view.snapshot().lastStatus());
            view.taskStarted("task-2", "run-2");
            assertNull(view.readHash("src/A.java"));
        }
    }

    @Test void approvalRequirementFailsClosedWithoutWriting() throws Exception {
        Path workspace = temp.resolve("approval");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            InteractiveSession session = session(workspace);
            session.startTask("task-approval");
            List<AgentEventType> events = new ArrayList<>();
            AgentEventPublisher publisher = new AgentEventPublisher(
                    "session-1", Clock.systemUTC());
            publisher.addSink(event -> events.add(event.metadata().eventType()));
            RuntimeEventBridge bridge = new RuntimeEventBridge(
                    publisher, session, new InteractiveRuntimeView(), state, workspace);
            bridge.taskStarted("task-approval", "run-approval", "run a command");
            AgentLoopObserver.ToolRequestFact request =
                    new AgentLoopObserver.ToolRequestFact(
                            "run_command", "{\"command\":\"[redacted]\"}", null);
            bridge.toolRequested("task-approval", 1, request);
            bridge.toolDecision("task-approval", 1, request,
                    ApprovalDecision.needsApproval("run_command",
                            ApprovalDecision.RiskLevel.HIGH,
                            "task-approval", "on-request"));
            assertTrue(bridge.failOnApprovalRequired());
            assertEquals(List.of(AgentEventType.TASK_STARTED,
                    AgentEventType.TOOL_REQUESTED), events);
            assertFalse(events.contains(AgentEventType.FILE_WRITTEN));
        }
    }

    @Test void approvalEventsMapRealDecisionsAndSuppressOnlyPlanCancelDuplicate()
            throws Exception {
        Path workspace = temp.resolve("approval-decisions");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            String taskId = "task-decisions";
            String runId = "run-decisions";
            state.tasks.createTask(taskId, "map approval decisions", "mock", null,
                    "workspace-write", 3);
            InteractiveSession session = session(workspace);
            session.startTask(taskId);
            StringWriter buffer = new StringWriter();
            RuntimeDisplayPreferences preferences = new RuntimeDisplayPreferences();
            preferences.setVerbose(true);
            PlainRuntimeEventRenderer renderer = new PlainRuntimeEventRenderer(
                    TerminalOutputPort.plain(new PrintWriter(buffer), () -> false),
                    preferences);
            AgentEventPublisher publisher = new AgentEventPublisher(
                    "session-1", Clock.systemUTC());
            List<AgentEvent> events = new ArrayList<>();
            publisher.addSink(events::add);
            publisher.addSink(renderer);
            RuntimeEventBridge bridge = new RuntimeEventBridge(
                    publisher, session, new InteractiveRuntimeView(), state, workspace);
            bridge.taskStarted(taskId, runId, "map approval decisions");
            InteractiveApprovalCoordinator.ApprovalEvents approvalEvents =
                    bridge.approvalEvents();

            InteractiveApprovalRequest approved = approvalRequest(
                    "approval-allow", taskId, runId,
                    InteractiveApprovalRequest.Kind.EDIT);
            approvalEvents.resolved(approved, ApprovalDecision.allow(
                    "write_file", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user approved this request"), false);

            InteractiveApprovalRequest rejected = approvalRequest(
                    "approval-deny", taskId, runId,
                    InteractiveApprovalRequest.Kind.EDIT);
            approvalEvents.resolved(rejected, ApprovalDecision.deny(
                    "write_file", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user rejected current action; task may continue"),
                    false);

            InteractiveApprovalRequest cancelledEdit = approvalRequest(
                    "edit-cancel", taskId, runId,
                    InteractiveApprovalRequest.Kind.EDIT);
            approvalEvents.resolved(cancelledEdit, ApprovalDecision.deny(
                    "write_file", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user cancelled task"), false);

            InteractiveApprovalRequest cancelledPlan = approvalRequest(
                    "plan-cancel", taskId, runId,
                    InteractiveApprovalRequest.Kind.PLAN);
            approvalEvents.resolved(cancelledPlan, ApprovalDecision.deny(
                    "structured_plan_approval", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user cancelled task"), false);
            approvalEvents.resolved(cancelledPlan, ApprovalDecision.deny(
                    "structured_plan_approval", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user did not approve Plan revision"), false);
            approvalEvents.resolved(cancelledPlan, ApprovalDecision.deny(
                    "structured_plan_approval", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user did not approve Plan revision"), false);

            InteractiveApprovalRequest otherPlan = approvalRequest(
                    "plan-other", taskId, runId,
                    InteractiveApprovalRequest.Kind.PLAN);
            approvalEvents.resolved(otherPlan, ApprovalDecision.deny(
                    "structured_plan_approval", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user did not approve Plan revision"), false);

            InteractiveApprovalRequest approximateCancel = approvalRequest(
                    "plan-approximate", taskId, runId,
                    InteractiveApprovalRequest.Kind.PLAN);
            approvalEvents.resolved(approximateCancel, ApprovalDecision.deny(
                    "structured_plan_approval", ApprovalDecision.RiskLevel.MEDIUM,
                    taskId, "interactive user cancelled task "), false);

            renderer.flush();
            List<String> approvalLines = buffer.toString().lines()
                    .filter(line -> line.startsWith("[Approval] ")).toList();
            assertEquals(List.of(
                    "[Approval] approved",
                    "[Approval] rejected",
                    "[Approval] cancelled",
                    "[Approval] cancelled",
                    "[Approval] rejected",
                    "[Approval] rejected",
                    "[Approval] rejected"), approvalLines);
            assertEquals(1, approvalLines.stream()
                    .filter("[Approval] approved"::equals).count());
            assertEquals(2, approvalLines.stream()
                    .filter("[Approval] cancelled"::equals).count());
            List<AgentEvents.ApprovalResolved> resolutions = events.stream()
                    .filter(AgentEvents.ApprovalResolved.class::isInstance)
                    .map(AgentEvents.ApprovalResolved.class::cast).toList();
            assertEquals(List.of("ALLOW", "DENY", "CANCELLED", "CANCELLED",
                            "DENY", "DENY", "DENY"),
                    resolutions.stream().map(AgentEvents.ApprovalResolved::decision)
                            .toList());
            assertEquals(1, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TOOL_CANCELLED).count());
            assertEquals(0, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TOOL_FAILED).count());
            assertEquals(0, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TOOL_COMPLETED).count());
            AgentEvents.ToolCancelled toolCancelled = events.stream()
                    .filter(AgentEvents.ToolCancelled.class::isInstance)
                    .map(AgentEvents.ToolCancelled.class::cast)
                    .findFirst().orElseThrow();
            assertEquals("write_file", toolCancelled.toolName());
            assertEquals("interactive user cancelled task",
                    toolCancelled.boundedReason());
            List<com.paicli.state.ApprovalStore.ApprovalRecord> persisted =
                    state.approvals.findByTask(taskId);
            assertEquals(2, persisted.size());
            assertEquals(List.of("edit-cancel", "plan-cancel"), persisted.stream()
                    .map(com.paicli.state.ApprovalStore.ApprovalRecord::toolCallId)
                    .toList());
            assertTrue(buffer.toString().contains(
                    "[Tool] write_file cancelled: interactive user cancelled task"));
        }
    }

    @Test void toolFailureIsTerminalAndPrecedesTaskFailure() throws Exception {
        Path workspace = temp.resolve("tool-failure");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            String taskId = "task-tool-failure";
            String runId = "run-tool-failure";
            state.tasks.createTask(taskId, "fail tool", "mock", null,
                    "workspace-write", 3);
            state.tasks.markRunning(taskId);
            state.codingRuns.create(runId, taskId,
                    com.paicli.state.CodingRunStore.KIND_INITIAL, null, 1, 0);
            InteractiveSession session = session(workspace);
            session.startTask(taskId);
            List<AgentEvent> events = new ArrayList<>();
            StringWriter buffer = new StringWriter();
            AgentEventPublisher publisher = new AgentEventPublisher(
                    "session-1", Clock.systemUTC());
            publisher.addSink(events::add);
            publisher.addSink(new PlainRuntimeEventRenderer(new PrintWriter(buffer)));
            RuntimeEventBridge bridge = new RuntimeEventBridge(
                    publisher, session, new InteractiveRuntimeView(), state, workspace);
            bridge.taskStarted(taskId, runId, "fail tool");
            AgentLoopObserver.ToolRequestFact request =
                    new AgentLoopObserver.ToolRequestFact(
                            "run_command", "{\"command\":\"[redacted]\"}", null);
            bridge.toolRequested(taskId, 1, request);
            bridge.toolDecision(taskId, 1, request, ApprovalDecision.allow(
                    "run_command", ApprovalDecision.RiskLevel.LOW, taskId, "allowed"));
            bridge.toolCompleted(taskId, 1, request,
                    new AgentLoopObserver.ToolCompletionFact(
                            false, "process exited 7", Map.of()));
            bridge.taskFailed(new CodingTaskResult(taskId, runId, null,
                    TaskStore.STATUS_FAILED, null, 0, "tool failed"));

            List<AgentEventType> types = events.stream()
                    .map(event -> event.metadata().eventType()).toList();
            assertTrue(types.indexOf(AgentEventType.TOOL_FAILED)
                    < types.indexOf(AgentEventType.TASK_FAILED));
            assertEquals(1, types.stream()
                    .filter(AgentEventType.TOOL_FAILED::equals).count());
            assertTrue(buffer.toString().contains("• Failed run_command"));
            assertTrue(buffer.toString().contains("process exited 7"));
            assertTrue(buffer.toString().contains("└─ Failed · 0 ms"));
        }
    }

    @Test void successfulCommandProjectsSeparatedBoundedStreamsAndExitStatus()throws Exception{
        Path workspace=temp.resolve("command-transcript");
        Files.createDirectories(workspace);
        try(StateStore state=new StateStore(workspace)){
            String taskId="task-command-transcript";String runId="run-command-transcript";
            state.tasks.createTask(taskId,"run","mock",null,"workspace-write",3);
            state.tasks.markRunning(taskId);
            state.codingRuns.create(runId,taskId,
                    com.paicli.state.CodingRunStore.KIND_INITIAL,null,1,0);
            InteractiveSession session=session(workspace);session.startTask(taskId);
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher("session-command",
                    Clock.systemUTC());publisher.addSink(events::add);
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session,
                    new InteractiveRuntimeView(),state,workspace);
            bridge.taskStarted(taskId,runId,"run");events.clear();
            AgentLoopObserver.ToolRequestFact request=new AgentLoopObserver.ToolRequestFact(
                    "run_command","{\"command\":\"[redacted]\"}",null);
            bridge.toolCompleted(taskId,1,request,new AgentLoopObserver.ToolCompletionFact(
                    true,null,Map.of("exitCode",7,"commandStatus","NON_ZERO",
                    "stdout","one\ntwo","stderr","warning","stdoutTruncated",true,
                    "stderrTruncated",false),"combined",12,3,true));

            AgentEvents.ToolCompleted completed=assertInstanceOf(
                    AgentEvents.ToolCompleted.class,events.getFirst());
            assertEquals(List.of("stdout | one","stdout | two","stderr | warning"),
                    completed.boundedItems());
            assertTrue(completed.boundedFacts().contains("Exit code: 7"));
            assertTrue(completed.boundedFacts().contains("Status: NON_ZERO"));
            assertTrue(completed.boundedFacts().contains("Stdout truncated: true"));
        }
    }

    @Test void incompleteResultPublishesOneCriticalInconclusiveAndKeepsDurableRunning()
            throws Exception {
        Path workspace = temp.resolve("inconclusive");
        Files.createDirectories(workspace);
        List<AgentEvent> events = new ArrayList<>();
        List<TerminalOutputPort.Priority> priorities = new ArrayList<>();
        TerminalOutputPort output = new TerminalOutputPort() {
            @Override public void writeLine(String line, Priority priority) {
                priorities.add(priority);
            }
            @Override public void writeLines(List<String> lines, Priority priority) {
                priorities.add(priority);
            }
            @Override public void flush() { }
        };
        InteractiveRuntimeEventRenderer renderer = new InteractiveRuntimeEventRenderer(
                output, new RuntimeDisplayPreferences());
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicReference<String> phaseBeforePresentation = new AtomicReference<>();
        AtomicReference<String> phaseAfterPresentation = new AtomicReference<>();
        AtomicInteger handoffs = new AtomicInteger();
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        try (StateStore state = new StateStore(workspace);
             InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                     workspace, (id, request, store, bridge) -> {
                         taskId.set(id);
                         store.tasks.createTask(id, request.input(), "mock", null,
                                 "workspace-write", 3);
                         store.tasks.markRunning(id);
                         String createdRunId = "run-inconclusive";
                         runId.set(createdRunId);
                         store.codingRuns.create(createdRunId, id,
                                 com.paicli.state.CodingRunStore.KIND_INITIAL,
                                 null, 1, 0);
                         phaseBeforePresentation.set(store.codingRuns
                                 .findById(createdRunId).orElseThrow().phase());
                         bridge.taskStarted(id, createdRunId, request.input());
                         phaseAfterPresentation.set(store.codingRuns
                                 .findById(createdRunId).orElseThrow().phase());
                         return new CodingTaskResult(id, createdRunId, null,
                                 TaskStore.STATUS_RUNNING, null, 0,
                                 "execution ended without a terminal result");
                     }, event -> {
                         events.add(event);
                         renderer.accept(event);
                     }, view, state)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            adapter.onTerminal(handoffs::incrementAndGet);
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(new InteractiveTaskRequest(
                            session.snapshot().sessionId(), workspace,
                            "inconclusive task", List.of())).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(TaskSubmissionResult.Status.INCOMPLETE,
                    adapter.lastResult().orElseThrow().status());
            assertEquals(TaskStore.STATUS_RUNNING,
                    state.tasks.findById(taskId.get()).orElseThrow().status());
            assertEquals(CodingRunStore.PHASE_PLANNING,
                    phaseBeforePresentation.get());
            assertEquals(phaseBeforePresentation.get(),
                    phaseAfterPresentation.get());
            assertEquals(phaseBeforePresentation.get(),
                    state.codingRuns.findById(runId.get()).orElseThrow().phase());
            assertNull(session.snapshot().currentTaskId());
            assertNull(view.snapshot().activeTaskId());
            assertEquals("INCOMPLETE", view.snapshot().lastStatus());
            assertEquals(1, handoffs.get());
            assertEquals(1, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TASK_INCONCLUSIVE).count());
            assertEquals(0, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TASK_COMPLETED).count());
            assertEquals(0, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TASK_FAILED).count());
            assertEquals(0, events.stream().filter(event ->
                    event.metadata().eventType() == AgentEventType.TASK_CANCELLED).count());
            assertEquals(TerminalOutputPort.Priority.CRITICAL, priorities.getLast());
        }
    }

    @Test void taskStartPresentationUsesAuthoritativeRunKind() throws Exception {
        assertTaskStartLine("initial", com.paicli.state.CodingRunStore.KIND_INITIAL,
                "[Task] Started: work");
        assertTaskStartLine("resume", com.paicli.state.CodingRunStore.KIND_RESUME,
                "[Run] Resumed: work");
        assertTaskStartLine("unknown", null,
                "[Run] Started: work");
    }

    @Test void existingExecutionGuardLifecycleBindsWithoutCopyingAuthority()throws Exception{
        Path workspace=temp.resolve("authority-binding");
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            RuntimeEventBridge bridge=new RuntimeEventBridge(new AgentEventPublisher(
                    "session-authority",Clock.systemUTC()),session(workspace),
                    new InteractiveRuntimeView(),state,workspace);
            bridge.executionAuthorityBound(admitted.guard());
            assertSame(admitted.guard(),bridge.executionGuard());
            assertFalse(bridge.executionGuard().current().snapshot().toString()
                    .contains("r3-test-lease"));
            bridge.clearExecutionGuard(admitted.guard());
            assertNull(bridge.executionGuard());
        }
    }

    private void assertCanonicalProjectionUnavailable(String suffix,boolean persistRevision,
            boolean closeBeforeRead)throws Exception{
        Path workspace=temp.resolve("canonical-plan-"+suffix);
        Files.createDirectories(workspace);
        StateStore state=new StateStore(workspace);
        try{
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            CanonicalId planId=CanonicalId.newId(EntityFamily.PLAN);
            if(persistRevision){
                var revision=approvedSingleStepPlan(state,admitted,"Durable goal",
                        "Durable step");
                planId=revision.planRevisionId();
                if(!closeBeforeRead){
                    Path database=workspace.resolve(".paicli/state/paicli.db");
                    try(var connection=DriverManager.getConnection(
                            "jdbc:sqlite:"+database.toAbsolutePath());
                        var statement=connection.createStatement()){
                        statement.executeUpdate("DROP TRIGGER r1c_plan_step_immutable");
                        statement.executeUpdate("UPDATE plan_step_definitions "
                                +"SET title='tampered' WHERE plan_revision_id='"
                                +planId.value()+"'");
                    }
                }
            }
            if("not-found".equals(suffix)){
                assertEquals(PlanAuthorityStore.Status.NOT_FOUND,
                        state.planAuthorities.readRevisionDefinition(
                                admitted.root().taskId(),planId).status());
            }else if("integrity".equals(suffix)){
                assertEquals(PlanAuthorityStore.Status.PLAN_REVISION_INTEGRITY_FAILURE,
                        state.planAuthorities.readRevisionDefinition(
                                admitted.root().taskId(),planId).status());
            }
            List<AgentEvent> events=new ArrayList<>();
            AgentEventPublisher publisher=new AgentEventPublisher("session-"+suffix,
                    Clock.systemUTC());
            publisher.addSink(events::add);
            RuntimeEventBridge bridge=new RuntimeEventBridge(publisher,session(workspace),
                    new InteractiveRuntimeView(),state,workspace);
            if(closeBeforeRead)state.close();

            bridge.planCreated(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),new PlanResult(planId.value(),
                            admitted.root().initialRun().id().value(),
                            admitted.root().taskId().value(),
                            "Injected goal\n1. Injected text step"));

            AgentEvents.PlanCreated event=assertInstanceOf(AgentEvents.PlanCreated.class,
                    events.getFirst());
            assertEquals("Plan projection unavailable",event.boundedPlanSummary());
            assertEquals(0,event.stepCount());
            assertEquals(List.of(),event.boundedSteps());
            assertEquals(planId.value(),event.planRevisionId());
            assertFalse(event.toString().contains("Injected"));
        }finally{
            state.close();
        }
    }

    private StructuredPlanModels.Revision approvedSingleStepPlan(StateStore state,
            R3ContextTestFixtures.Admitted admitted,String goal,String stepTitle){
        var authority=admitted.guard().current().snapshot();
        CanonicalId planId=CanonicalId.newId(EntityFamily.PLAN);
        var step=new StructuredPlanModels.StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,stepTitle,
                "Read exactly one requested file",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ,
                List.of(exactReadRequirement("runtime-event-read")),"b".repeat(64));
        Instant now=Instant.now();
        var draft=new StructuredPlanModels.Revision(planId,authority.taskId(),1,goal,
                "The requested file was read","none","none","tool evidence",
                "0".repeat(64),"MODEL","runtime-event-bridge-test",authority.runId(),
                now,List.of(step),List.of(),List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        var revision=new StructuredPlanModels.Revision(planId,authority.taskId(),1,
                draft.goal(),draft.acceptanceCriteria(),draft.assumptions(),draft.risks(),
                draft.verificationStrategy(),PlanCanonicalizer.hash(draft),draft.sourceType(),
                draft.provenance(),authority.runId(),now,draft.steps(),draft.dependencies(),
                draft.lineage(),StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.propose(
                authority,now.plusSeconds(1),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(
                authority,now.plusSeconds(2),planId,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                authority,now.plusSeconds(3),planId,1,0,"approval-plan-projection",
                "user","runtime-event-bridge-test","approved canonical projection").status());
        return revision;
    }

    private static StructuredPlanModels.ToolRequirement exactReadRequirement(String id) {
        String json = "{\"path\":\"AGENTS.md\",\"maxLines\":null,"
                + "\"includeInternal\":false}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH, json,
                com.paicli.state.PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement(id, 0, "read_file",
                PlanCompletionModels.EffectClass.READ, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private InteractiveSession session(Path workspace) {
        InteractiveSession session = new InteractiveSession(
                "session-1", workspace, "mock", null, "workspace-write",
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        return session;
    }

    private static InteractiveApprovalRequest approvalRequest(String requestId,
            String taskId, String runId, InteractiveApprovalRequest.Kind kind) {
        if(kind==InteractiveApprovalRequest.Kind.PLAN)return new InteractiveApprovalRequest(
                requestId, "session-1", taskId, runId,
                1,"structured_plan_approval",null,ApprovalDecision.RiskLevel.MEDIUM,
                "workspace-write",null,kind,"bounded approval summary",null,
                Instant.now().plusSeconds(60),"pln_123456789abc4def8abc123456789abc",1,
                java.util.List.of(new InteractiveApprovalRequest.PlanStepProjection(
                        0,"Inspect","Inspect the requested scope")));
        return new InteractiveApprovalRequest(requestId, "session-1", taskId, runId,
                1, kind == InteractiveApprovalRequest.Kind.PLAN
                        ? "structured_plan_approval" : "write_file",
                kind == InteractiveApprovalRequest.Kind.EDIT ? "src/A.java" : null,
                ApprovalDecision.RiskLevel.MEDIUM, "workspace-write", null, kind,
                "bounded approval summary", null, Instant.now().plusSeconds(60));
    }

    private void assertTaskStartLine(String suffix, String runKind, String expected)
            throws Exception {
        Path workspace = temp.resolve("run-kind-" + suffix);
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            String taskId = "task-" + suffix;
            String runId = "run-" + suffix;
            state.tasks.createTask(taskId, "work", "mock", null,
                    "workspace-write", 3);
            state.tasks.markRunning(taskId);
            if (runKind != null) {
                state.codingRuns.create(runId, taskId, runKind, null, 1, 0);
            }
            InteractiveSession session = session(workspace);
            session.startTask(taskId);
            StringWriter buffer = new StringWriter();
            AgentEventPublisher publisher = new AgentEventPublisher(
                    "session-1", Clock.systemUTC());
            publisher.addSink(new PlainRuntimeEventRenderer(new PrintWriter(buffer)));
            String phaseBefore = state.codingRuns.findById(runId)
                    .map(CodingRunStore.CodingRunRecord::phase).orElse(null);
            new RuntimeEventBridge(publisher, session,
                    new InteractiveRuntimeView(), state, workspace)
                    .taskStarted(taskId, runId, "work");
            assertTrue(buffer.toString().contains(expected));
            if (runKind != null) {
                assertNotNull(phaseBefore);
                assertFalse(List.of(CodingRunStore.PHASE_COMPLETED,
                        CodingRunStore.PHASE_FAILED,
                        CodingRunStore.PHASE_CANCELLED).contains(phaseBefore));
                assertEquals(phaseBefore, state.codingRuns.findById(runId)
                        .orElseThrow().phase());
            }
        }
    }
}
