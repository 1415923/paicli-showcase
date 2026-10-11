package com.paicli.runtime;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.provider.ModelProvider;
import com.paicli.state.InstructionManifestCandidate;
import com.paicli.state.ProtectedInstructionArtifact;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aDurableModels.TaskContextPointer;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.RootAdmissionTransaction;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.state.PlanCompletionProfilePolicy;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.model.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class StructuredPlanRepairTest {
    private static final Instant AT = Instant.parse("2026-07-30T04:00:00Z");
    private static final String VALID = """
            {"acceptanceCriteria":["files are listed"],"assumptions":[],
             "risks":[],"verificationStrategy":"inspect the tool result",
             "steps":[{"title":"Inspect","definition":"List workspace files",
             "dependsOn":[],"completionRule":"ALL_REQUIRED_EVIDENCE",
             "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
             "toolRequirements":[{"requirementId":"list-workspace","requirementOrder":0,
             "toolName":"list_files","effectClass":"READ",
             "authoritySelector":{"kind":"EXACT_PATH","path":".","includeInternal":false},
             "minSuccesses":1,"maxOccurrences":1,
             "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;
    private static final String INVALID_SCHEMA = """
            {"acceptanceCriteria":["secret-response-sentinel"],
             "assumptions":[],"risks":[],"steps":[]}
            """;
    private static final String ADVISORY_VALID = """
            {"acceptanceCriteria":["files are listed"],"assumptions":[],"risks":[],
             "verificationStrategy":"inspect the result","steps":[
             {"title":"Inspect","definition":"List workspace files","dependsOn":[]}]}
            """;
    private static final String UAT_SHAPED_UNPRODUCIBLE = """
            {"acceptanceCriteria":["files are described"],"assumptions":[],"risks":[],
             "verificationStrategy":"review the result",
             "steps":[
              {"title":"Traverse","definition":"List workspace files","dependsOn":[],
               "completionRule":"SNAPSHOT_CREATED","requiredEvidenceKinds":["SNAPSHOT"],
               "effectClass":"READ","toolRequirements":[]},
              {"title":"Describe","definition":"Describe each listed file","dependsOn":[0],
               "completionRule":"VERIFICATION_PASSED",
               "requiredEvidenceKinds":["VERIFICATION_RESULT"],"effectClass":"READ",
               "toolRequirements":[]}]}
            """;

    @TempDir Path workspace;

    @Test
    void oneSchemaRepairSucceedsWithoutReplayingInvalidResponseBody() throws Exception {
        try (StateStore state = new StateStore(workspace.resolve("repair-success"))) {
            Seed seed = seed(state);
            RecordingProvider provider = new RecordingProvider(
                    ModelResponse.finalAnswer(INVALID_SCHEMA),
                    ModelResponse.finalAnswer(VALID));
            PlanService service = new PlanService(provider, state.plans);

            var revision = service.generateStructured(seed.task(), seed.run(),
                    "list workspace files", "", seed.guard(state), 1, AT.plusSeconds(2));

            assertEquals(1, revision.steps().size());
            assertEquals(2, provider.calls);
            assertEquals(2, provider.messages.get(1).size());
            String repairRequest = provider.messages.get(1).stream()
                    .map(AgentMessage::content).reduce("", (left, right) -> left + "\n" + right);
            assertTrue(repairRequest.contains("verificationStrategy"));
            assertTrue(repairRequest.contains("$.steps"));
            assertFalse(repairRequest.contains("secret-response-sentinel"));
        }
    }

    @Test
    void repairFailureStopsAfterExactlyTwoProviderCalls() throws Exception {
        try (StateStore state = new StateStore(workspace.resolve("repair-failure"))) {
            Seed seed = seed(state);
            RecordingProvider provider = new RecordingProvider(
                    ModelResponse.finalAnswer(INVALID_SCHEMA),
                    ModelResponse.finalAnswer(INVALID_SCHEMA),
                    ModelResponse.finalAnswer(VALID));
            PlanService service = new PlanService(provider, state.plans);

            var failure = assertThrows(PlanService.PlanGenerationException.class,
                    () -> service.generateStructured(seed.task(), seed.run(),
                            "list workspace files", "", seed.guard(state), 1,
                            AT.plusSeconds(2)));

            assertTrue(failure.getMessage().contains("STRUCTURED_PLAN_REPAIR_FAILED"));
            assertFalse(failure.getMessage().contains("secret-response-sentinel"));
            assertEquals(2, provider.calls);
        }
    }

    @Test
    void syntaxFailureDoesNotTriggerSchemaRepair() throws Exception {
        try (StateStore state = new StateStore(workspace.resolve("syntax-failure"))) {
            Seed seed = seed(state);
            RecordingProvider provider = new RecordingProvider(
                    ModelResponse.finalAnswer("{\"steps\":,}"),
                    ModelResponse.finalAnswer(VALID));
            PlanService service = new PlanService(provider, state.plans);

            var failure = assertThrows(PlanService.PlanGenerationException.class,
                    () -> service.generateStructured(seed.task(), seed.run(),
                            "list workspace files", "", seed.guard(state), 1,
                            AT.plusSeconds(2)));

            assertTrue(failure.getMessage().contains("JSON_SYNTAX_ERROR"));
            assertEquals(1, provider.calls);
        }
    }

    @Test
    void oneBoundedCompletionProfileRepairPrecedesProposalAndUsesRunCapabilities()
            throws Exception {
        try (StateStore state = new StateStore(workspace.resolve("profile-repair-success"))) {
            Seed seed = seed(state);
            RecordingProvider provider = new RecordingProvider(
                    ModelResponse.finalAnswer(UAT_SHAPED_UNPRODUCIBLE),
                    ModelResponse.finalAnswer(VALID));
            PlanService service = new PlanService(provider, state.plans);

            var revision = service.generateStructured(seed.task(), seed.run(),
                    "list workspace files", "", seed.guard(state), 1, AT.plusSeconds(2),
                    new PlanCompletionProfilePolicy.Capabilities(false));

            assertEquals("R1C_A_STRUCTURED_PLAN_PROFILE_REPAIR", revision.provenance());
            assertEquals(List.of(EvidenceKind.TOOL_CALL),
                    revision.steps().getFirst().requiredEvidenceKinds());
            assertEquals(2, provider.calls);
            String repairRequest = provider.messages.get(1).stream()
                    .map(AgentMessage::content).reduce("", (left, right) -> left + "\n" + right);
            assertTrue(repairRequest.contains("VERIFICATION_UNAVAILABLE"));
            assertTrue(repairRequest.contains("SNAPSHOT_REQUIRES_WRITE_EFFECT"));
            assertTrue(repairRequest.contains("No verification command is configured"));
            assertFalse(repairRequest.contains("files are described"));
        }
    }

    @Test
    void unproducibleCompletionProfileStopsBeforeApprovalAfterOneRepair() throws Exception {
        try (StateStore state = new StateStore(workspace.resolve("profile-repair-failure"))) {
            Seed seed = seed(state);
            RecordingProvider provider = new RecordingProvider(
                    ModelResponse.finalAnswer(UAT_SHAPED_UNPRODUCIBLE),
                    ModelResponse.finalAnswer(UAT_SHAPED_UNPRODUCIBLE));
            PlanService service = new PlanService(provider, state.plans);

            var failure = assertThrows(PlanService.PlanGenerationException.class,
                    () -> service.generateStructured(seed.task(), seed.run(),
                            "list workspace files", "", seed.guard(state), 1,
                            AT.plusSeconds(2),
                            new PlanCompletionProfilePolicy.Capabilities(false)));

            assertTrue(failure.getMessage().contains("COMPLETION_PROFILE_UNPRODUCIBLE"));
            assertEquals(2, provider.calls);
            assertTrue(state.planAuthorities.readActive(seed.task()).status()
                    != com.paicli.state.PlanAuthorityStore.Status.SUCCESS);
        }
    }

    @Test
    void advisoryWireUsesOneBoundedRepairAndProducesNoStrictToolRequirements() throws Exception {
        try (StateStore state = new StateStore(workspace.resolve("advisory-repair"))) {
            Seed seed = seed(state);
            RecordingProvider provider = new RecordingProvider(
                    ModelResponse.finalAnswer(INVALID_SCHEMA),
                    ModelResponse.finalAnswer(ADVISORY_VALID));
            PlanService service = new PlanService(provider, state.plans).advisoryPlanMode();

            var revision = service.generateStructured(seed.task(), seed.run(),
                    "list workspace files", "", seed.guard(state), 1, AT.plusSeconds(2),
                    new PlanCompletionProfilePolicy.Capabilities(false));

            assertEquals(2, provider.calls);
            assertEquals(com.paicli.state.StructuredPlanModels.Revision.CANONICAL_SCHEMA_V2,
                    revision.canonicalSchemaVersion());
            assertTrue(revision.steps().stream().allMatch(
                    step -> step.toolRequirements().isEmpty()));
            assertTrue(revision.provenance().endsWith(":ADVISORY_WIRE_V1"));
        }
    }

    private static Seed seed(StateStore state) throws Exception {
        CanonicalId thread = CanonicalId.newId(EntityFamily.THREAD);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        CanonicalId generation = CanonicalId.newId(EntityFamily.GENERATION);
        String manifest = "manifest-" + UUID.randomUUID();
        ProtectedInstructionArtifact artifact = ProtectedInstructionArtifact.inlineUtf8(
                "artifact-" + UUID.randomUUID(), "frozen", "v1",
                "TASK_AUTHORITY", AT);
        var entry = new InstructionManifestCandidate.Entry(
                "entry-" + UUID.randomUUID(), "PROJECT_FILE", "AGENTS.md",
                "WORKSPACE", InstructionManifestCandidate.Status.APPLIED, 0,
                artifact.contentHash(), artifact.id(), "UTF8", null, null);
        var candidate = new InstructionManifestCandidate(
                1, "root-paicli-agents-v1", List.of(entry));
        var request = new RootAdmissionTransaction.Request(
                new WorkThread(thread, "workspace", "goal", "OPEN", AT, AT),
                new DurableTask(task, thread, null, "goal", "CREATED",
                        null, null, AT, null),
                manifest, candidate, List.of(artifact),
                new DurableRun(run, task, thread, "INITIAL", null, manifest,
                        candidate.canonicalHash(), AT, null),
                new TaskContextGeneration(generation, task, null, run, manifest,
                        candidate.canonicalHash(), "COMMITTED", "{}", AT, AT),
                new TaskContextPointer(task, generation, run, 0),
                new RootAdmissionTransaction.RuntimeTask(task.value(), "goal",
                        "mock", "model", "deny", 3, "RUNNING"),
                new RootAdmissionTransaction.RuntimeRun(run.value(), task.value(),
                        "INITIAL", "PLANNING", "", 1, 0,
                        "skills-disabled", "hooks-disabled"),
                PlanApplicability.PLAN_REQUIRED, AT);
        assertInstanceOf(RootAdmissionTransaction.Success.class,
                state.r1aDurable.admitRootTaskAtomically(request));
        assertEquals(OwnershipResult.SUCCESS, state.r1aDurable.acquireOwnership(
                task, "runtime", run, "lease", AT, AT.plusSeconds(60)));
        return new Seed(task, run);
    }

    private record Seed(CanonicalId task, CanonicalId run) {
        FencedExecutionGuard guard(StateStore state) throws Exception {
            var lease = state.r1aDurable.readOwnership(task).orElseThrow();
            return new FencedExecutionGuard(state,
                    TaskExecutionAuthority.initial(lease), Duration.ofSeconds(30),
                    () -> AT.plusSeconds(1));
        }
    }

    private static final class RecordingProvider implements ModelProvider {
        private final ConcurrentLinkedQueue<ModelResponse> responses =
                new ConcurrentLinkedQueue<>();
        private final List<List<AgentMessage>> messages = new ArrayList<>();
        private int calls;

        private RecordingProvider(ModelResponse... values) {
            responses.addAll(List.of(values));
        }

        @Override
        public ModelResponse chat(List<AgentMessage> input,
                List<ToolDefinition> tools) {
            calls++;
            messages.add(List.copyOf(input));
            ModelResponse response = responses.poll();
            if (response == null) throw new AssertionError("provider script exhausted");
            return response;
        }
    }
}
