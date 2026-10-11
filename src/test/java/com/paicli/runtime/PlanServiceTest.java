package com.paicli.runtime;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.MockModelProvider;
import com.paicli.provider.ModelProvider;
import com.paicli.state.CodingRunStore;
import com.paicli.state.PlanCompletionProfilePolicy;
import com.paicli.state.StateStore;
import com.paicli.state.TaskPlanStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanServiceTest {

    @TempDir
    Path workspace;

    @Test
    void generatesToolFreeSanitizedBoundedPlanAndPersistsIt() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-001", "run-001")) {
            AtomicReference<List<AgentMessage>> capturedMessages = new AtomicReference<>();
            AtomicReference<List<ToolDefinition>> capturedTools = new AtomicReference<>();
            ModelProvider provider = (messages, tools) -> {
                capturedMessages.set(messages);
                capturedTools.set(tools);
                return ModelResponse.finalAnswer(
                        "1. inspect\nAuthorization: Bearer private-token\n" + "x".repeat(500));
            };
            PlanService service = new PlanService(
                    provider, stateStore.plans, new PersistenceSanitizer(), 160,
                    () -> "plan-001");

            PlanResult result = service.generate("task-001", "run-001", "fix the build");

            assertEquals("plan-001", result.planId());
            assertTrue(capturedTools.get().isEmpty());
            assertTrue(capturedMessages.get().getFirst().content().contains("fix the build"));
            assertFalse(result.planText().contains("private-token"));
            assertTrue(result.planText().contains("<redacted>"));
            assertTrue(result.planText().contains("[truncated; original_bytes="));
            assertTrue(result.planText().getBytes(StandardCharsets.UTF_8).length <= 160);
            TaskPlanStore.TaskPlanRecord stored = stateStore.plans.findByRun("run-001")
                    .orElseThrow();
            assertEquals(TaskPlanStore.STATUS_GENERATED, stored.status());
            assertEquals(result.planText(), stored.planText());
        }
    }

    @Test
    void deterministicMockProviderProducesPredictablePlan() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-002", "run-002")) {
            PlanService service = new PlanService(
                    new MockModelProvider(List.of(ModelResponse.finalAnswer("1. edit\n2. test"))),
                    stateStore.plans, new PersistenceSanitizer(), 1024,
                    () -> "plan-mock");

            PlanResult result = service.generate("task-002", "run-002", "task");

            assertEquals("1. edit\n2. test", result.planText());
            assertEquals("plan-mock", result.planId());
        }
    }

    @Test
    void planningPromptIncludesProvidedUntrustedSkillContext() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-skill", "run-skill")) {
            AtomicReference<List<AgentMessage>> captured = new AtomicReference<>();
            ModelProvider provider = (messages, tools) -> {
                captured.set(messages);
                return ModelResponse.finalAnswer("plan");
            };
            PlanService service = new PlanService(provider, stateStore.plans);

            service.generate("task-skill", "run-skill", "build java",
                    "<untrusted_skill_context>bounded skill</untrusted_skill_context>");

            String prompt = captured.get().getFirst().content();
            assertTrue(prompt.contains("build java"));
            assertTrue(prompt.contains("<untrusted_skill_context>"));
            assertTrue(prompt.contains("bounded skill"));
        }
    }

    @Test
    void structuredPlanningAndSchemaRepairPromptsUseSingleOccurrenceDefaults() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-structured-prompt", "run-structured-prompt")) {
            PlanService service = new PlanService(
                    new MockModelProvider(List.of(ModelResponse.finalAnswer("unused"))),
                    stateStore.plans);
            var planning = PlanService.class.getDeclaredMethod("structuredPlanningPrompt",
                    String.class, String.class, PlanCompletionProfilePolicy.Capabilities.class);
            planning.setAccessible(true);
            var repair = PlanService.class.getDeclaredMethod("structuredRepairPrompt", List.class);
            repair.setAccessible(true);

            String planningPrompt = (String) planning.invoke(service, "task", "",
                    new PlanCompletionProfilePolicy.Capabilities(false));
            String repairPrompt = (String) repair.invoke(service, List.of("invalid"));

            for (String prompt : List.of(planningPrompt, repairPrompt)) {
                assertTrue(prompt.contains("minSuccesses=1"));
                assertTrue(prompt.contains("maxOccurrences=1"));
                assertTrue(prompt.contains("freshnessPolicy=RESUME_REUSE_ONLY"));
                assertTrue(prompt.contains("polling or repeated fresh execution"));
                assertTrue(prompt.contains("maxOccurrences greater than 1"));
                assertTrue(prompt.contains("freshnessPolicy=FRESH_EXECUTION_REQUIRED"));
            }
        }
    }

    @Test
    void toolCallResponseFailsWithoutPersistingPlan() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-003", "run-003")) {
            ModelProvider provider = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("inspect",
                            new ToolCall("list_files", Map.of("path", ".")))));
            PlanService service = new PlanService(provider, stateStore.plans);

            PlanService.PlanGenerationException error = assertThrows(
                    PlanService.PlanGenerationException.class,
                    () -> service.generate("task-003", "run-003", "task"));

            assertTrue(error.getMessage().contains("tool call"));
            assertTrue(stateStore.plans.findByRun("run-003").isEmpty());
        }
    }

    @Test
    void modelErrorAndBlankContentFailWithoutPersistingPlan() throws Exception {
        assertRejectedFinal("task-004", "run-004", "[MODEL_ERROR] provider unavailable",
                "MODEL_ERROR");
        assertRejectedFinal("task-005", "run-005", "   ", "empty content");
    }

    @Test
    void providerExceptionIsReportedWithoutPersistingPlan() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-006", "run-006")) {
            ModelProvider provider = (messages, tools) -> {
                throw new IllegalStateException("network detail");
            };
            PlanService service = new PlanService(provider, stateStore.plans);

            PlanService.PlanGenerationException error = assertThrows(
                    PlanService.PlanGenerationException.class,
                    () -> service.generate("task-006", "run-006", "task"));

            assertTrue(error.getMessage().contains("PROVIDER_REQUEST_EXCEPTION"));
            assertTrue(stateStore.plans.findByRun("run-006").isEmpty());
        }
    }

    @Test
    void interruptedPlanningEscapesAsCancellationInsteadOfFailedPlan()
            throws Exception {
        try (StateStore stateStore = preparedStateStore(
                "task-cancel", "run-cancel")) {
            ModelProvider provider = (messages, tools) -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("transport interrupted");
            };
            PlanService service = new PlanService(provider, stateStore.plans);
            try {
                assertThrows(CancellationToken.TaskCancellationException.class,
                        () -> service.generate("task-cancel", "run-cancel", "task"));
                assertTrue(stateStore.plans.findByRun("run-cancel").isEmpty());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void typedPlanningProviderFailuresRemainActionableAndBounded() throws Exception {
        java.util.Map<ModelResponse.ProviderErrorKind, String> expected =
                java.util.Map.of(
                        ModelResponse.ProviderErrorKind.PROVIDER_CONNECTION_FAILURE,
                        "PROVIDER_CONNECTION_FAILURE",
                        ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
                        "PROVIDER_TIMEOUT",
                        ModelResponse.ProviderErrorKind.PROVIDER_MODEL_UNAVAILABLE,
                        "PROVIDER_MODEL_UNAVAILABLE",
                        ModelResponse.ProviderErrorKind.PROVIDER_HTTP_FAILURE,
                        "PROVIDER_HTTP_FAILURE",
                        ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE,
                        "PROVIDER_MALFORMED_RESPONSE",
                        ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE,
                        "PROVIDER_EMPTY_RESPONSE",
                        ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_SERIALIZATION_FAILURE,
                        "PROVIDER_REQUEST_SERIALIZATION_FAILURE");
        int index = 0;
        for (var entry : expected.entrySet()) {
            String taskId = "task-provider-" + index;
            String runId = "run-provider-" + index++;
            try (StateStore stateStore = preparedStateStore(taskId, runId)) {
                PlanService service = new PlanService((messages, tools) ->
                        ModelResponse.providerError(entry.getKey()), stateStore.plans);
                PlanService.PlanGenerationException error = assertThrows(
                        PlanService.PlanGenerationException.class,
                        () -> service.generate(taskId, runId, "task"));
                assertTrue(error.getMessage().contains(entry.getValue()));
                assertFalse(error.getMessage().contains("prompt"));
                assertTrue(stateStore.plans.findByRun(runId).isEmpty());
            }
        }
    }

    private void assertRejectedFinal(String taskId, String runId, String content,
                                     String expectedMessage) throws Exception {
        try (StateStore stateStore = preparedStateStore(taskId, runId)) {
            PlanService service = new PlanService(
                    new MockModelProvider(List.of(ModelResponse.finalAnswer(content))),
                    stateStore.plans);

            PlanService.PlanGenerationException error = assertThrows(
                    PlanService.PlanGenerationException.class,
                    () -> service.generate(taskId, runId, "task"));

            assertTrue(error.getMessage().contains(expectedMessage));
            assertTrue(stateStore.plans.findByRun(runId).isEmpty());
        }
    }

    private StateStore preparedStateStore(String taskId, String runId) throws Exception {
        StateStore stateStore = new StateStore(workspace.resolve(taskId));
        stateStore.tasks.createTask(taskId, "goal", "mock", null, "on-request", 10);
        stateStore.codingRuns.create(runId, taskId, CodingRunStore.KIND_INITIAL,
                "", 1, 0);
        return stateStore;
    }
}
