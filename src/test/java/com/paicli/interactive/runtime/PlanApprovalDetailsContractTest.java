package com.paicli.interactive.runtime;

import com.paicli.event.AgentEventPublisher;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.approval.InteractiveApprovalCoordinator;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.approval.CanonicalPlanPresentation;
import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.interactive.diff.PreWriteDiffService;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.runtime.PlanApprovalPort;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanCompletionModels;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredPlanModels;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlanApprovalDetailsContractTest {
    @TempDir Path workspace;

    @Test
    void PLAN_APPROVAL_DETAILS_REREAD_FULL_DURABLE_CANONICAL_REVISION() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            var authority = admitted.guard().current().snapshot();
            String fullDefinition = "完整 canonical 说明：" + "核对 durable evidence。".repeat(30);
            var revision = revision(authority.taskId(), authority.runId(), fullDefinition);
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority, Instant.now(), revision).status());

            InteractiveSession session = session(authority.taskId(), authority.runId());
            InteractiveRuntimeView view = new InteractiveRuntimeView();
            view.taskStarted(authority.taskId().value(), authority.runId().value());
            InteractiveCancellationController cancellation =
                    new InteractiveCancellationController();
            InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                    session.snapshot().sessionId(), cancellation, Clock.systemUTC());
            RuntimeEventBridge bridge = new RuntimeEventBridge(
                    new AgentEventPublisher(session.snapshot().sessionId(), Clock.systemUTC()),
                    session, view, state, workspace, coordinator, cancellation);
            coordinator.beginTask(authority.taskId().value(), authority.runId().value(),
                    "on-request", new PreWriteDiffService(
                            new SafetyPolicy(workspace, 5, 8192), null), bridge.approvalEvents());

            CompletableFuture<PlanApprovalPort.Decision> future = CompletableFuture.supplyAsync(
                    () -> coordinator.requestApproval(revision, "bounded preview"));
            InteractiveApprovalRequest request = awaitRequest(coordinator);
            var details = coordinator.respond(request.requestId(), "d");

            assertFalse(details.resolved());
            assertTrue(coordinator.pendingRequest().isPresent());
            assertTrue(details.lines().contains(
                    "Plan revision: " + revision.planRevisionId().value()));
            assertTrue(details.lines().contains("Plan hash: " + revision.canonicalHash()));
            assertTrue(details.lines().contains("Plan canonical schema: V3"));
            assertTrue(details.lines().contains("Plan mode: DEFAULT_ADVISORY_PLAN"));
            assertTrue(details.lines().contains(CanonicalPlanPresentation.ADVISORY_WARNING));
            assertTrue(details.lines().contains("   Definition: " + fullDefinition));
            assertTrue(details.lines().stream().anyMatch(line ->
                    line.contains("Advisory dependencies:")),details.lines().toString());
            assertFalse(details.lines().stream().anyMatch(line->
                    line.contains("requirementId=")||line.contains("toolName=")
                            ||line.contains("requiredSlots=")),details.lines().toString());
            assertEquals(revision.canonicalHash(), PlanCanonicalizer.hash(revision));

            StateStoreInteractiveQueryFacade queries = new StateStoreInteractiveQueryFacade(
                    workspace, view);
            List<String> planDetails = queries.plan(session.snapshot(), true);
            assertTrue(planDetails.containsAll(details.lines()), planDetails.toString());
            List<String> summary = queries.plan(session.snapshot(), false);
            assertTrue(summary.contains("Plan canonical schema: V3"), summary.toString());
            assertTrue(summary.contains(CanonicalPlanPresentation.ADVISORY_WARNING),
                    summary.toString());
            assertTrue(summary.stream().anyMatch(line->line.contains("Progress: advisory")),
                    summary.toString());
            assertFalse(summary.stream().anyMatch(line->line.contains("Requirements:")),
                    summary.toString());

            coordinator.respond(request.requestId(), "n");
            assertFalse(future.get(2, TimeUnit.SECONDS).approved());
        }
    }

    @Test
    void PLAN_APPROVAL_PRESENTATION_FAILURE_IS_TYPED_CLEARS_PENDING_AND_FAILS_CLOSED()
            throws Exception {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        InteractiveApprovalCoordinator coordinator = coordinator(task, run,
                new InteractiveApprovalCoordinator.ApprovalEvents() {
                    @Override public List<String> planDetails(InteractiveApprovalRequest request) {
                        throw new IllegalStateException("durable read unavailable");
                    }
                });
        var revision = revision(task, run, "full definition");
        CompletableFuture<PlanApprovalPort.Decision> first = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(revision, "bounded preview"));
        InteractiveApprovalRequest request = awaitRequest(coordinator);

        var response = coordinator.respond(request.requestId(), "d");
        assertTrue(response.resolved());
        assertEquals(List.of("PLAN_APPROVAL_PRESENTATION_FAILED"), response.lines());
        assertTrue(coordinator.pendingRequest().isEmpty());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> first.get(2, TimeUnit.SECONDS));
        assertInstanceOf(InteractiveApprovalCoordinator.PlanApprovalPresentationException.class,
                failure.getCause());

        CompletableFuture<PlanApprovalPort.Decision> second = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(revision, "bounded preview"));
        InteractiveApprovalRequest next = awaitRequest(coordinator);
        coordinator.respond(next.requestId(), "n");
        assertFalse(second.get(2, TimeUnit.SECONDS).approved());
    }

    @Test
    void V2_DETAILS_DOES_NOT_FABRICATE_REQUIREMENTS_AND_HASH_IS_UNCHANGED() {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        StructuredPlanModels.Revision revision = v2Revision(task, run);
        String before = revision.canonicalHash();

        List<String> lines = CanonicalPlanPresentation.details(revision);

        assertEquals(before, revision.canonicalHash());
        assertEquals(before, PlanCanonicalizer.hash(revision));
        assertTrue(lines.contains("Plan canonical schema: V2"));
        assertTrue(lines.contains(
                "   Structured requirements: unavailable in V2 (not fabricated)"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("toolName=")));
    }

    @Test
    void HASH_MISMATCH_PROJECTION_FAILS_CLOSED() {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        StructuredPlanModels.Revision valid = revision(task, run, "definition");
        StructuredPlanModels.Revision mismatch = new StructuredPlanModels.Revision(
                valid.planRevisionId(), valid.taskId(), valid.revisionNumber(), valid.goal(),
                valid.acceptanceCriteria(), valid.assumptions(), valid.risks(),
                valid.verificationStrategy(), "f".repeat(64), valid.sourceType(),
                valid.provenance(), valid.createdByRunId(), valid.createdAt(), valid.steps(),
                valid.dependencies(), valid.lineage(), valid.canonicalSchemaVersion());

        assertThrows(IllegalStateException.class,
                () -> CanonicalPlanPresentation.details(mismatch));
    }

    @Test
    void APPROVAL_DETAILS_REVERIFIES_DURABLE_HASH_AND_CLEARS_PENDING() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            var authority = admitted.guard().current().snapshot();
            var revision = revision(authority.taskId(), authority.runId(), "definition");
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority, Instant.now(), revision).status());
            InteractiveSession session = session(authority.taskId(), authority.runId());
            InteractiveRuntimeView view = new InteractiveRuntimeView();
            view.taskStarted(authority.taskId().value(), authority.runId().value());
            InteractiveCancellationController cancellation =
                    new InteractiveCancellationController();
            InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                    session.snapshot().sessionId(), cancellation, Clock.systemUTC());
            RuntimeEventBridge bridge = new RuntimeEventBridge(
                    new AgentEventPublisher(session.snapshot().sessionId(), Clock.systemUTC()),
                    session, view, state, workspace, coordinator, cancellation);
            coordinator.beginTask(authority.taskId().value(), authority.runId().value(),
                    "on-request", new PreWriteDiffService(
                            new SafetyPolicy(workspace, 5, 8192), null), bridge.approvalEvents());
            CompletableFuture<PlanApprovalPort.Decision> future = CompletableFuture.supplyAsync(
                    () -> coordinator.requestApproval(revision, "bounded preview"));
            InteractiveApprovalRequest request = awaitRequest(coordinator);

            Path database = workspace.resolve(".paicli/state/paicli.db");
            try (var connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + database.toAbsolutePath());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DROP TRIGGER r1c_plan_step_immutable");
                statement.executeUpdate("UPDATE plan_step_definitions SET title='tampered' "
                        + "WHERE plan_revision_id='" + revision.planRevisionId().value() + "'");
            }

            var response = coordinator.respond(request.requestId(), "d");
            assertEquals(List.of("PLAN_APPROVAL_PRESENTATION_FAILED"), response.lines());
            assertTrue(response.resolved());
            assertTrue(coordinator.pendingRequest().isEmpty());
            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void MISSING_REQUIREMENT_PROJECTION_FAILS_CLOSED() {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        var step = new StructuredPlanModels.StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Inspect", "definition",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ, List.of(), "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new StructuredPlanModels.Revision(
                CanonicalId.newId(EntityFamily.PLAN), task, 1, "goal", "acceptance",
                "none", "none", "verify", "0".repeat(64), "MODEL", "test", run,
                Instant.now(), List.of(step), List.of(), List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3));
    }

    @Test
    void REQUIREMENT_DETAILS_DO_NOT_LEAK_PRIVATE_DATA() {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        String secret = "PRIVATE_APPROVAL_SECRET";
        String json = "{\"command\":\"echo Authorization: Bearer " + secret + "\"}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_COMMAND, json,
                com.paicli.state.PlanToolRequirementPolicy.digest(json));
        var requirement = new StructuredPlanModels.ToolRequirement("safe-command", 0,
                "run_command", PlanCompletionModels.EffectClass.GENERAL, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
        StructuredPlanModels.Revision revision = revisionWithRequirement(task, run, requirement);

        String rendered = String.join("\n", CanonicalPlanPresentation.details(revision));

        assertFalse(rendered.contains(secret), rendered);
        assertFalse(rendered.contains("Bearer " + secret), rendered);
        assertTrue(rendered.contains("<redacted>"), rendered);
    }

    @Test
    void PLAN_DETAILS_FOR_ALL_SIXTEEN_STEPS_FIT_SHELL_BOUND_WITHOUT_TRUNCATION() {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        StructuredPlanModels.Revision revision = revisionWithSteps(task, run, 16);

        List<String> lines = CanonicalPlanPresentation.details(revision);
        String rendered = String.join("\n", lines);

        assertTrue(lines.size() < 100, "canonical details must fit the shell line bound: "
                + lines.size());
        assertEquals(16, lines.stream().filter(line -> line.startsWith("   Definition: "))
                .count());
        assertEquals(16, lines.stream().filter(line -> line.startsWith("   Scope: "))
                .count());
        assertEquals(16, lines.stream().filter(line -> line.startsWith("   Requirement "))
                .count());
        assertTrue(rendered.contains("definition-16"));
        assertTrue(rendered.contains("requirementId=requirement-16"));
        assertTrue(rendered.contains("path=README.md"));
        assertFalse(rendered.contains("[truncated]"));
    }

    private InteractiveApprovalCoordinator coordinator(CanonicalId task, CanonicalId run,
            InteractiveApprovalCoordinator.ApprovalEvents events) {
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session", new InteractiveCancellationController(), Clock.systemUTC());
        coordinator.beginTask(task.value(), run.value(), "on-request",
                new PreWriteDiffService(new SafetyPolicy(workspace, 5, 8192), null), events);
        return coordinator;
    }

    private InteractiveSession session(CanonicalId task, CanonicalId run) {
        InteractiveSession session = new InteractiveSession("session-plan-details", workspace,
                "mock", null, "on-request", InteractiveSession.TerminalMode.PLAIN,
                Instant.now());
        session.start();
        session.startTask(task.value(), run.value());
        return session;
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

    private static StructuredPlanModels.Revision revision(CanonicalId task, CanonicalId run,
            String definition) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        var step = new StructuredPlanModels.StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Inspect", definition,
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ,
                List.of(exactReadRequirement("approval-details-read")), "a".repeat(64));
        Instant at = Instant.parse("2026-08-02T04:00:00Z");
        var draft = new StructuredPlanModels.Revision(plan, task, 1, "goal", "acceptance",
                "none", "none", "verify", "0".repeat(64), "MODEL", "test", run,
                at, List.of(step), List.of(), List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        return new StructuredPlanModels.Revision(plan, task, 1, draft.goal(),
                draft.acceptanceCriteria(), draft.assumptions(), draft.risks(),
                draft.verificationStrategy(), PlanCanonicalizer.hash(draft), draft.sourceType(),
                draft.provenance(), run, at, draft.steps(), draft.dependencies(), draft.lineage(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
    }

    private static StructuredPlanModels.Revision revisionWithRequirement(CanonicalId task,
            CanonicalId run, StructuredPlanModels.ToolRequirement requirement) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        var step = new StructuredPlanModels.StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Execute", "definition",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                requirement.effectClass(), List.of(requirement), "a".repeat(64));
        Instant at = Instant.parse("2026-08-03T01:00:00Z");
        var draft = new StructuredPlanModels.Revision(plan, task, 1, "goal", "acceptance",
                "none", "none", "verify", "0".repeat(64), "MODEL", "test", run,
                at, List.of(step), List.of(), List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        return new StructuredPlanModels.Revision(plan, task, 1, draft.goal(),
                draft.acceptanceCriteria(), draft.assumptions(), draft.risks(),
                draft.verificationStrategy(), PlanCanonicalizer.hash(draft), draft.sourceType(),
                draft.provenance(), run, at, draft.steps(), draft.dependencies(), draft.lineage(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
    }

    private static StructuredPlanModels.Revision revisionWithSteps(CanonicalId task,
            CanonicalId run, int count) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        List<StructuredPlanModels.StepDefinition> steps =
                java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new StructuredPlanModels.StepDefinition(
                        CanonicalId.newId(EntityFamily.PLAN_STEP),
                        CanonicalId.newId(EntityFamily.PLAN_STEP), index - 1,
                        "Step " + index, "definition-" + index,
                        PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                        List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                        PlanCompletionModels.EffectClass.READ,
                        List.of(exactReadRequirement("requirement-" + index)),
                        "a".repeat(64)))
                .toList();
        Instant at = Instant.parse("2026-08-03T01:00:00Z");
        var draft = new StructuredPlanModels.Revision(plan, task, 1, "goal", "acceptance",
                "none", "none", "verify", "0".repeat(64), "MODEL", "test", run,
                at, steps, List.of(), List.of(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        return new StructuredPlanModels.Revision(plan, task, 1, draft.goal(),
                draft.acceptanceCriteria(), draft.assumptions(), draft.risks(),
                draft.verificationStrategy(), PlanCanonicalizer.hash(draft), draft.sourceType(),
                draft.provenance(), run, at, draft.steps(), draft.dependencies(), draft.lineage(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
    }

    private static StructuredPlanModels.Revision v2Revision(CanonicalId task, CanonicalId run) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        var step = new StructuredPlanModels.StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Historical", "definition",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ, "a".repeat(64));
        Instant at = Instant.parse("2026-08-03T01:00:00Z");
        var draft = new StructuredPlanModels.Revision(plan, task, 1, "goal", "acceptance",
                "none", "none", "verify", "0".repeat(64), "MODEL", "test", run,
                at, List.of(step), List.of(), List.of());
        return new StructuredPlanModels.Revision(plan, task, 1, draft.goal(),
                draft.acceptanceCriteria(), draft.assumptions(), draft.risks(),
                draft.verificationStrategy(), PlanCanonicalizer.hash(draft), draft.sourceType(),
                draft.provenance(), run, at, draft.steps(), draft.dependencies(), draft.lineage());
    }

    private static StructuredPlanModels.ToolRequirement exactReadRequirement(String id) {
        String json = "{\"path\":\"README.md\",\"maxLines\":null,"
                + "\"includeInternal\":false}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH, json,
                com.paicli.state.PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement(id, 0, "read_file",
                PlanCompletionModels.EffectClass.READ, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }
}
