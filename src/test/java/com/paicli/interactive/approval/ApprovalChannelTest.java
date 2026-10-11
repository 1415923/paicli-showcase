package com.paicli.interactive.approval;

import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.interactive.diff.PreWriteDiffService;
import com.paicli.model.ToolCall;
import com.paicli.safety.ApprovalDecision;
import com.paicli.safety.SafetyPolicy;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanCompletionModels;
import com.paicli.state.StructuredPlanModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalChannelTest {
    @TempDir Path temp;

    @Test void showDiffDoesNotResolveAndApplyIsIdentityBound() throws Exception {
        Path file = source();
        byte[] original = Files.readAllBytes(file);
        InteractiveApprovalCoordinator coordinator = coordinator();
        CompletableFuture<ApprovalDecision> outcome = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1, write("return 2;"),
                        ApprovalDecision.needsApproval("write_file",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(temp.resolve(".paicli")));
        assertFalse(coordinator.respond("wrong", "y").resolved());
        ApprovalInteractionResult shown = coordinator.respond(request.requestId(), "d");
        assertFalse(shown.resolved());
        assertTrue(shown.lines().stream().anyMatch(line -> line.startsWith("--- a/")));
        assertFalse(outcome.isDone());
        assertTrue(coordinator.respond(request.requestId(), "y").resolved());
        assertTrue(coordinator.pendingRequest().isEmpty());
        assertFalse(coordinator.respond(request.requestId(), "n").resolved());
        assertTrue(outcome.get(2, TimeUnit.SECONDS).isAllowed());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void emptyInputRepromptsAndTaskScopedGrantExpires() throws Exception {
        source();
        InteractiveApprovalCoordinator coordinator = coordinator();
        CompletableFuture<ApprovalDecision> first = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1, write("return 2;"),
                        ApprovalDecision.needsApproval("write_file",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertFalse(coordinator.respond(request.requestId(), "").resolved());
        assertFalse(first.isDone());
        assertTrue(coordinator.respond(request.requestId(), "n").resolved());
        assertFalse(first.get(2, TimeUnit.SECONDS).isAllowed());

        CompletableFuture<ApprovalDecision> grant = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 2, write("return 3;"),
                        ApprovalDecision.needsApproval("write_file",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        request = awaitRequest(coordinator);
        assertTrue(coordinator.respond(request.requestId(), "a").resolved());
        assertTrue(grant.get(2, TimeUnit.SECONDS).isAllowed());
        assertTrue(coordinator.resolveApproval("task-1", 3, write("return 4;"),
                ApprovalDecision.needsApproval("write_file",
                        ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")).isAllowed());
        coordinator.endTask("task-1");
    }

    @Test void invalidResponsesRepromptUntilAnExplicitDecision() throws Exception {
        source();
        InteractiveApprovalCoordinator coordinator = coordinator();
        CompletableFuture<ApprovalDecision> outcome = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1, write("return 2;"),
                        ApprovalDecision.needsApproval("write_file",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertFalse(coordinator.respond(request.requestId(), "maybe").resolved());
        assertFalse(coordinator.respond(request.requestId(), "later").resolved());
        assertFalse(coordinator.respond(request.requestId(), "unknown").resolved());
        assertFalse(outcome.isDone());
        assertTrue(coordinator.respond(request.requestId(), "n").resolved());
        assertFalse(outcome.get(2, TimeUnit.SECONDS).isAllowed());
    }

    @Test void deniedWriteDoesNotPublishOrPrepareDiff() throws Exception {
        source();
        InteractiveCancellationController cancellation = new InteractiveCancellationController();
        java.util.concurrent.atomic.AtomicInteger proposals = new java.util.concurrent.atomic.AtomicInteger();
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session-1", cancellation, Clock.systemUTC());
        coordinator.beginTask("task-1", "run-1", "read-only",
                new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null),
                new InteractiveApprovalCoordinator.ApprovalEvents() {
                    @Override public void proposed(String proposalId, String taskId,
                            String runId, com.paicli.interactive.diff.PreWriteDiff diff) {
                        proposals.incrementAndGet();
                    }
                });
        ApprovalDecision denied = coordinator.resolveApproval("task-1", 1, write("return 2;"),
                ApprovalDecision.deny("write_file", ApprovalDecision.RiskLevel.HIGH,
                        "task-1", "read-only"));
        assertFalse(denied.isAllowed());
        assertEquals(0, proposals.get());
        assertTrue(coordinator.pendingRequest().isEmpty());
    }

    @Test void commandUsesCommandPromptAndCannotReceiveSafeEditGrant() throws Exception {
        InteractiveApprovalCoordinator coordinator = coordinator();
        ToolCall command = new ToolCall("run_command", Map.of(
                "command", "mvn test && secret-command"));
        CompletableFuture<ApprovalDecision> outcome = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1, command,
                        ApprovalDecision.needsApproval("run_command",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertEquals(InteractiveApprovalRequest.Kind.COMMAND, request.kind());
        assertEquals("[Proposed Command]", request.heading());
        assertEquals("Run command?", request.promptLabel());
        assertFalse(request.promptLabel().contains("Apply edit"));
        assertNull(request.diff());
        assertTrue(coordinator.respond(request.requestId(), "a").resolved());
        assertFalse(outcome.get(2, TimeUnit.SECONDS).isAllowed());
    }

    @Test void gitDiffUsesReadPromptRatherThanEditPrompt() throws Exception {
        InteractiveApprovalCoordinator coordinator = coordinator();
        CompletableFuture<ApprovalDecision> outcome = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1,
                        new ToolCall("git_diff", Map.of("subcommand", "diff")),
                        ApprovalDecision.needsApproval("git_diff",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertEquals(InteractiveApprovalRequest.Kind.GIT_READ, request.kind());
        assertEquals("[Proposed Git Read]", request.heading());
        assertEquals("Run Git read?", request.promptLabel());
        assertNull(request.diff());
        assertTrue(coordinator.respond(request.requestId(), "n").resolved());
        assertFalse(outcome.get(2, TimeUnit.SECONDS).isAllowed());
    }

    @Test void duplicateNameNewFileIsExplicitAndDefaultsToReject() throws Exception {
        source();
        InteractiveApprovalCoordinator coordinator = coordinator();
        ToolCall newFile = new ToolCall("write_file", Map.of(
                "path", "src/main/java/other/A.java",
                "content", "package other; class A {}\n",
                "overwrite", false));
        CompletableFuture<ApprovalDecision> outcome = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1, newFile,
                        ApprovalDecision.needsApproval("write_file",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        assertEquals(InteractiveApprovalRequest.Kind.NEW_FILE, request.kind());
        assertEquals("Create file?", request.promptLabel());
        assertTrue(request.heading().contains("NEW FILE"));
        assertNotNull(request.warning());
        assertTrue(request.warning().contains("same name"));
        assertFalse(coordinator.respond(request.requestId(), "").resolved());
        assertFalse(outcome.isDone());
        assertTrue(coordinator.respond(request.requestId(), "n").resolved());
        assertFalse(outcome.get(2, TimeUnit.SECONDS).isAllowed());
        assertFalse(Files.exists(temp.resolve("src/main/java/other/A.java")));
    }

    @Test void existingFilesOnlyTaskRejectsCreationBeforeOpeningApproval() throws Exception {
        source();
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session-1", new InteractiveCancellationController(), Clock.systemUTC());
        coordinator.beginTask("task-1", "run-1", "on-request",
                "Only modify existing files; do not create files.",
                new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null),
                InteractiveApprovalCoordinator.ApprovalEvents.NOOP);
        ApprovalDecision decision = coordinator.resolveApproval("task-1", 1,
                new ToolCall("write_file", Map.of("path", "src/New.java",
                        "content", "class New {}\n", "overwrite", false)),
                ApprovalDecision.needsApproval("write_file",
                        ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt"));
        assertFalse(decision.isAllowed());
        assertTrue(decision.reason().contains("existing files only"));
        assertTrue(coordinator.pendingRequest().isEmpty());
        assertFalse(Files.exists(temp.resolve("src/New.java")));
    }
    @Test void existingEditRequiresReadEvidenceAndDetectsPostReadDrift() throws Exception {
        Path file = source();
        InteractiveCancellationController cancellation = new InteractiveCancellationController();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        String readHash = sha256(Files.readAllBytes(file));
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session-1", cancellation, Clock.systemUTC());
        coordinator.beginTask("task-1", "run-1", "on-request",
                new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null),
                new InteractiveApprovalCoordinator.ApprovalEvents() {
                    @Override public void requested(InteractiveApprovalRequest request) {
                        requests.incrementAndGet();
                    }
                });
        ApprovalDecision unread = coordinator.resolveApproval("task-1", 1, write("return 2;"),
                ApprovalDecision.needsApproval("write_file",
                        ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt"));
        assertTrue(unread.reason().contains("WRITE_TARGET_NOT_READ"));
        assertEquals(0, requests.get());
        coordinator.endTask("task-1");

        coordinator.beginTask("task-2", "run-2", "on-request",
                new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null),
                new InteractiveApprovalCoordinator.ApprovalEvents() {
                    @Override public String readHash(String relativePath) { return readHash; }
                    @Override public void requested(InteractiveApprovalRequest request) {
                        requests.incrementAndGet();
                    }
                });
        Files.writeString(file, "class A { int value() { return 9; } }\n");
        ApprovalDecision drifted = coordinator.resolveApproval("task-2", 1, write("return 3;"),
                ApprovalDecision.needsApproval("write_file",
                        ApprovalDecision.RiskLevel.HIGH, "task-2", "prompt"));
        assertTrue(drifted.reason().contains("WRITE_TARGET_CHANGED_AFTER_READ"));
        assertEquals(0, requests.get());
    }

    @Test void rejectedDuplicateIsNotPromptedAndRejectBudgetIsBounded() throws Exception {
        source();
        InteractiveApprovalCoordinator coordinator = coordinator();
        for (String replacement : java.util.List.of("return 2;", "return 3;")) {
            CompletableFuture<ApprovalDecision> decision = CompletableFuture.supplyAsync(() ->
                    coordinator.resolveApproval("task-1", 1, write(replacement),
                            ApprovalDecision.needsApproval("write_file",
                                    ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
            InteractiveApprovalRequest request = awaitRequest(coordinator);
            coordinator.respond(request.requestId(), "n");
            assertFalse(decision.get(2, TimeUnit.SECONDS).isAllowed());
        }
        ApprovalDecision duplicate = coordinator.resolveApproval("task-1", 3,
                write("return 2;"), ApprovalDecision.needsApproval("write_file",
                        ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt"));
        assertTrue(duplicate.reason().contains("DUPLICATE_PROPOSAL"));
        ApprovalDecision exhausted = coordinator.resolveApproval("task-1", 4,
                write("return 4;"), ApprovalDecision.needsApproval("write_file",
                        ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt"));
        assertTrue(exhausted.reason().contains("REJECT_BUDGET_EXHAUSTED"));
        assertTrue(coordinator.pendingRequest().isEmpty());
    }
    @Test void cancelDecisionSetsTokenBeforeWorkerResumesAndMarksProposalCancelled()
            throws Exception {
        Path file = source();
        String readHash = sha256(Files.readAllBytes(file));
        InteractiveCancellationController cancellation = new InteractiveCancellationController();
        java.util.List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session-1", cancellation, Clock.systemUTC());
        coordinator.beginTask("task-1", "run-1", "on-request",
                new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null),
                new InteractiveApprovalCoordinator.ApprovalEvents() {
                    @Override public String readHash(String relativePath) { return readHash; }
                    @Override public void cancellationRequested(boolean forced) {
                        statuses.add(forced ? "FORCED" : "CANCELLATION_REQUESTED");
                    }
                    @Override public void proposalResolved(
                            InteractiveApprovalRequest request, String status) {
                        statuses.add(status);
                    }
                });
        CompletableFuture<ApprovalDecision> outcome = CompletableFuture.supplyAsync(() ->
                coordinator.resolveApproval("task-1", 1, write("return 2;"),
                        ApprovalDecision.needsApproval("write_file",
                                ApprovalDecision.RiskLevel.HIGH, "task-1", "prompt")));
        InteractiveApprovalRequest request = awaitRequest(coordinator);
        ApprovalInteractionResult interaction = coordinator.respond(request.requestId(), "c");
        assertTrue(interaction.cancelTask());
        assertTrue(cancellation.cancellationRequested());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> outcome.get(2, TimeUnit.SECONDS));
        assertEquals(java.util.List.of("CANCELLED", "CANCELLATION_REQUESTED"), statuses);
        assertTrue(coordinator.pendingRequest().isEmpty());
    }

    @Test void PLAN_APPROVAL_CANONICAL_PROJECTION_PASS()throws Exception{
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run=CanonicalId.newId(EntityFamily.RUN);
        InteractiveApprovalCoordinator coordinator=new InteractiveApprovalCoordinator(
                "session-plan",new InteractiveCancellationController(),Clock.systemUTC());
        coordinator.beginTask(task.value(),run.value(),"on-request",
                new PreWriteDiffService(new SafetyPolicy(temp,5,8192),null),
                InteractiveApprovalCoordinator.ApprovalEvents.NOOP);
        StructuredPlanModels.Revision first=revision(task,run,1,15);
        var decision=CompletableFuture.supplyAsync(()->coordinator.requestApproval(
                first,"untrusted flat text must not own the projection"));
        InteractiveApprovalRequest request=awaitRequest(coordinator);
        assertEquals(first.planRevisionId().value(),request.planRevisionId());
        assertEquals(15,request.planStepCount());
        assertEquals(15,request.boundedPlanSteps().size());
        assertEquals("Step 1",request.boundedPlanSteps().getFirst().title());
        assertEquals("Step 15",request.boundedPlanSteps().getLast().title());
        coordinator.respond(request.requestId(),"y");
        assertTrue(decision.get(2,TimeUnit.SECONDS).approved());

        StructuredPlanModels.Revision revised=revision(task,run,2,2);
        var second=CompletableFuture.supplyAsync(()->coordinator.requestApproval(revised,"revision"));
        InteractiveApprovalRequest revisedRequest=awaitRequest(coordinator);
        assertNotEquals(request.planRevisionId(),revisedRequest.planRevisionId());
        assertFalse(second.isDone(),"a new immutable revision requires a new approval");
        coordinator.respond(revisedRequest.requestId(),"n");
        assertFalse(second.get(2,TimeUnit.SECONDS).approved());
    }

    private static StructuredPlanModels.Revision revision(
            CanonicalId task,CanonicalId run,int number,int count){
        List<StructuredPlanModels.StepDefinition> steps=new java.util.ArrayList<>();
        for(int index=0;index<count;index++)steps.add(new StructuredPlanModels.StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),index,"Step "+(index+1),
                "Canonical definition "+(index+1),
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ,"a".repeat(64)));
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        var draft=new StructuredPlanModels.Revision(plan,task,number,"goal","acceptance",
                "none","none","verify","0".repeat(64),"USER","test",run,Instant.now(),
                steps,List.of(),List.of());
        return new StructuredPlanModels.Revision(plan,task,number,draft.goal(),
                draft.acceptanceCriteria(),draft.assumptions(),draft.risks(),
                draft.verificationStrategy(),com.paicli.state.PlanCanonicalizer.hash(draft),
                draft.sourceType(),draft.provenance(),run,draft.createdAt(),steps,List.of(),List.of());
    }
    private InteractiveApprovalCoordinator coordinator() throws Exception {
        InteractiveCancellationController cancellation = new InteractiveCancellationController();
        InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                "session-1", cancellation, Clock.systemUTC());
        Path observedFile = temp.resolve("src/main/java/demo/A.java");
        if (!Files.exists(observedFile)) source();
        String readHash = sha256(Files.readAllBytes(
                observedFile));
        coordinator.beginTask("task-1", "run-1", "on-request",
                new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null),
                new InteractiveApprovalCoordinator.ApprovalEvents() {
                    @Override public String readHash(String relativePath) {
                        return "src/main/java/demo/A.java".equals(relativePath)
                                ? readHash : null;
                    }
                });
        return coordinator;
    }
    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
    private Path source() throws Exception {
        Path file = temp.resolve("src/main/java/demo/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A { int value() { return 1; } }\n");
        return file;
    }
    private ToolCall write(String replacement) {
        return new ToolCall("write_file", Map.of(
                "path", "src/main/java/demo/A.java",
                "content", "class A { int value() { " + replacement + " } }\n",
                "overwrite", true));
    }
    private static InteractiveApprovalRequest awaitRequest(
            InteractiveApprovalCoordinator coordinator) throws Exception {
        for (int i=0;i<100;i++) {
            var request=coordinator.pendingRequest();
            if(request.isPresent()) return request.get();
            Thread.sleep(10);
        }
        throw new AssertionError("approval request not published");
    }
}
