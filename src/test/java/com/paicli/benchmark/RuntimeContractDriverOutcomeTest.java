package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeContractDriverOutcomeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TASKS = Map.of(
            "task-025", "CONTINUE",
            "task-026", "CONTINUE",
            "task-027", "COMPACT",
            "task-028", "COMPACT",
            "task-029", "RECOVERY",
            "task-030", "RECOVERY");

    @Test
    void frozenSixTaskSuccessMatrixProducesMachineReadableContinuingOutcome()
            throws Exception {
        for (var entry : TASKS.entrySet()) {
            JsonNode task = frozenTask(entry.getKey());
            assertEquals(entry.getValue(), task.path("category").asText());
            FormalDriverResult result = RuntimeContractDriverOutcome.from(
                    entry.getKey(), entry.getValue(), true, "TASK_COMPLETED");
            assertEquals(FormalDriverResult.OutcomeClass.TASK_SUCCESS,
                    result.outcomeClass());
            assertEquals(FormalDriverResult.CampaignAction.CONTINUE,
                    result.campaignAction());
            assertTrue(result.evidenceComplete());
        }
    }

    @Test
    void frozenSixTaskAssertionFailureMatrixProducesTypedRowsAndContinues()
            throws Exception {
        for (var entry : TASKS.entrySet()) {
            JsonNode task = frozenTask(entry.getKey());
            List<String> assertions = JSON.convertValue(
                    task.path("oracle").path("runtimeAssertions"),
                    JSON.getTypeFactory().constructCollectionType(List.class, String.class));
            assertFalse(assertions.isEmpty());
            String reason = entry.getValue() + "_ASSERTION_FAILURE";
            FormalDriverResult result = RuntimeContractDriverOutcome.from(
                    entry.getKey(), entry.getValue(), false, reason);
            assertEquals(FormalDriverResult.OutcomeClass.TYPED_TASK_FAILURE,
                    result.outcomeClass());
            assertEquals(List.of(FormalDriverResult.FailureCategory
                    .RUNTIME_CONTRACT_ASSERTION_FAILURE), result.failureCategories());
            assertEquals(FormalDriverResult.CampaignAction.CONTINUE,
                    result.campaignAction());
            assertTrue(result.evidenceComplete());
        }
    }

    @Test
    void followUpNotEligibleIsTypedForFrozenContinueTasksAndSerializesBeforeExit()
            throws Exception {
        for (String taskId : List.of("task-025", "task-026")) {
            FormalDriverResult result = RuntimeContractDriverOutcome.from(
                    taskId, "CONTINUE", false, "FOLLOW_UP_NOT_ELIGIBLE");
            Path output = Files.createTempFile("runtime-driver-", ".json");
            try {
                Map<String, Object> driver = new LinkedHashMap<>();
                driver.put("taskId", taskId);
                driver.put("cohort", "PAICLI_RUNTIME_CONTRACT");
                driver.put("runtime", "PAICLI");
                driver.put("formalDriverResult", result.toMap());
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), driver);
                JsonNode persisted = JSON.readTree(output.toFile());
                assertEquals("TYPED_TASK_FAILURE", persisted.path("formalDriverResult")
                        .path("outcomeClass").asText());
                assertEquals("FOLLOW_UP_NOT_ELIGIBLE", persisted.path("formalDriverResult")
                        .path("terminalReason").asText());
                assertEquals("CONTINUE", persisted.path("formalDriverResult")
                        .path("campaignAction").asText());
            } finally {
                Files.deleteIfExists(output);
            }
        }
    }

    @Test
    void unknownDomainOrTaskMismatchEscapesAsHarnessInvalidityCandidate() {
        assertThrows(IllegalArgumentException.class, () ->
                RuntimeContractDriverOutcome.from("task-026", "CONTINUE", false,
                        "SOME_EXCEPTION_TEXT"));
        assertThrows(IllegalArgumentException.class, () ->
                RuntimeContractDriverOutcome.from("task-026", "RECOVERY", false,
                        "RECOVERY_ASSERTION_FAILURE"));
        assertThrows(IllegalArgumentException.class, () ->
                RuntimeContractDriverOutcome.from("task-031", "CONTINUE", false,
                        "FOLLOW_UP_NOT_ELIGIBLE"));
    }

    private static JsonNode frozenTask(String taskId) throws Exception {
        Path path = Path.of("benchmarks", "coding", "fixtures", taskId, "task.json");
        return JSON.readTree(path.toFile());
    }
}
