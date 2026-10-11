package com.paicli.interactive.runtime;

import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventPublisher;
import com.paicli.event.AgentEvents;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.approval.InteractiveApprovalCoordinator;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.interactive.diff.PreWriteDiffService;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.runtime.PlanApprovalPort;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanCompletionModels;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredPlanModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlanApprovalProjectionContractTest {
    @TempDir Path workspace;

    @Test
    void PLAN_DISPLAY_PROJECTION_BOUNDED_IS_CODE_POINT_SAFE() {
        String title = "🧪".repeat(121);
        String description = "说明🧪".repeat(100);
        InteractiveApprovalRequest.PlanStepProjection projection =
                new InteractiveApprovalRequest.PlanStepProjection(0, title, description);

        assertEquals(InteractiveApprovalRequest.PREVIEW_TITLE_CODE_POINTS,
                codePoints(projection.boundedTitle()));
        assertEquals(InteractiveApprovalRequest.PREVIEW_DESCRIPTION_CODE_POINTS,
                codePoints(projection.boundedDescription()));
        assertTrue(projection.boundedTitle().endsWith("…"));
        assertTrue(projection.boundedDescription().endsWith("…"));
        assertFalse(hasUnpairedSurrogate(projection.boundedTitle()));
        assertFalse(hasUnpairedSurrogate(projection.boundedDescription()));
    }

    @Test
    void PLAN_APPROVAL_PREVIEW_INCLUDES_ALL_SIXTEEN_STEPS() throws Exception {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        InteractiveApprovalCoordinator coordinator = coordinator(task, run,
                InteractiveApprovalCoordinator.ApprovalEvents.NOOP);
        var revision = revision(task, run, 16, 40);

        CompletableFuture<PlanApprovalPort.Decision> decision = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(revision, "summary"));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertEquals(16, request.planStepCount());
        assertEquals(16, request.boundedPlanSteps().size());
        assertEquals(15, request.boundedPlanSteps().getLast().order());
        coordinator.respond(request.requestId(), "n");
        assertFalse(decision.get(2, TimeUnit.SECONDS).approved());
    }

    @Test
    void PLAN_FIVE_STEP_LONG_DESCRIPTION_PASSES_REAL_COORDINATOR_BRIDGE_EVENT_CHAIN()
            throws Exception {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentEventPublisher publisher = new AgentEventPublisher("session-plan-bounds",
                Clock.systemUTC());
        publisher.addSink(events::add);
        InteractiveSession session = new InteractiveSession("session-plan-bounds", workspace,
                "mock", null, "on-request", InteractiveSession.TerminalMode.PLAIN,
                Instant.now());
        session.start();
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        view.taskStarted(task.value(), run.value());
        InteractiveCancellationController cancellation =
                new InteractiveCancellationController();
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                session.snapshot().sessionId(), cancellation, Clock.systemUTC());

        try (StateStore state = new StateStore(workspace)) {
            RuntimeEventBridge bridge = new RuntimeEventBridge(publisher, session, view, state,
                    workspace, coordinator, cancellation);
            coordinator.beginTask(task.value(), run.value(), "on-request",
                    new PreWriteDiffService(new SafetyPolicy(workspace, 5, 8192), null),
                    bridge.approvalEvents());
            var revision = revision(task, run, 5, 282);

            CompletableFuture<PlanApprovalPort.Decision> decision =
                    CompletableFuture.supplyAsync(
                            () -> coordinator.requestApproval(revision, "summary"));
            InteractiveApprovalRequest request = awaitRequest(coordinator);
            AgentEvents.ApprovalRequested event = awaitApprovalEvent(events);

            assertEquals(5, event.planStepCount());
            assertEquals(3, event.planSchemaVersion());
            assertEquals(5, event.boundedPlanSteps().size());
            assertTrue(event.boundedPlanSteps().stream()
                    .allMatch(step->step.requirements().isEmpty()));
            assertEquals(com.paicli.interactive.approval.CanonicalPlanPresentation
                    .ADVISORY_WARNING,event.warning());
            assertEquals(InteractiveApprovalRequest.PREVIEW_DESCRIPTION_CODE_POINTS,
                    codePoints(event.boundedPlanSteps().getLast().boundedDescription()));
            assertTrue(event.boundedPlanSteps().getLast().boundedDescription().endsWith("…"));
            coordinator.respond(request.requestId(), "y");
            assertTrue(decision.get(2, TimeUnit.SECONDS).approved());
        }
    }

    private InteractiveApprovalCoordinator coordinator(CanonicalId task, CanonicalId run,
            InteractiveApprovalCoordinator.ApprovalEvents events) {
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session", new InteractiveCancellationController(), Clock.systemUTC());
        coordinator.beginTask(task.value(), run.value(), "on-request",
                new PreWriteDiffService(new SafetyPolicy(workspace, 5, 8192), null), events);
        return coordinator;
    }

    private static InteractiveApprovalRequest awaitRequest(
            InteractiveApprovalCoordinator coordinator) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var request = coordinator.pendingRequest();
            if (request.isPresent()) return request.orElseThrow();
            Thread.onSpinWait();
        }
        throw new AssertionError("Plan approval request was not published");
    }

    private static AgentEvents.ApprovalRequested awaitApprovalEvent(
            List<AgentEvent> events) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var event = events.stream()
                    .filter(AgentEvents.ApprovalRequested.class::isInstance)
                    .map(AgentEvents.ApprovalRequested.class::cast)
                    .findFirst();
            if (event.isPresent()) return event.orElseThrow();
            Thread.onSpinWait();
        }
        throw new AssertionError("Plan approval event was not published");
    }

    private static StructuredPlanModels.Revision revision(CanonicalId task, CanonicalId run,
            int count, int lastDefinitionLength) {
        List<StructuredPlanModels.StepDefinition> steps = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int definitionLength = index == count - 1 ? lastDefinitionLength : 40;
            steps.add(new StructuredPlanModels.StepDefinition(
                    CanonicalId.newId(EntityFamily.PLAN_STEP),
                    CanonicalId.newId(EntityFamily.PLAN_STEP), index, "Step " + (index + 1),
                    "d".repeat(definitionLength),
                    PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                    List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                    PlanCompletionModels.EffectClass.READ,
                    List.of(readRequirement("preview-read-" + index)), "a".repeat(64)));
        }
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        Instant createdAt = Instant.parse("2026-08-02T04:00:00Z");
        var draft = new StructuredPlanModels.Revision(plan, task, 1, "goal", "acceptance",
                "none", "none", "verify", "0".repeat(64), "MODEL", "test", run,
                createdAt, steps, List.of(), List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        return new StructuredPlanModels.Revision(plan, task, 1, draft.goal(),
                draft.acceptanceCriteria(), draft.assumptions(), draft.risks(),
                draft.verificationStrategy(), PlanCanonicalizer.hash(draft), draft.sourceType(),
                draft.provenance(), run, createdAt, steps, List.of(), List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
    }

    private static StructuredPlanModels.ToolRequirement readRequirement(String id) {
        String json = "{\"path\":\"README.md\",\"maxLines\":null,"
                + "\"includeInternal\":false}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH, json,
                com.paicli.state.PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement(id, 0, "read_file",
                PlanCompletionModels.EffectClass.READ, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
