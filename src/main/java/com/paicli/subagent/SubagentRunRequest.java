package com.paicli.subagent;

import com.paicli.provider.ModelProvider;
import com.paicli.subagent.verdict.VerdictExtractionMode;

import java.util.Objects;

public record SubagentRunRequest(
        String taskId,
        String codingRunId,
        String stage,
        String occurrenceKey,
        int attemptNumber,
        SubagentDefinition definition,
        String taskInput,
        ModelProvider provider,
        String providerName,
        VerdictExtractionMode extractionMode,
        SubagentCodeTarget codeTarget
) {
    public SubagentRunRequest(
            String taskId,
            String codingRunId,
            String stage,
            String occurrenceKey,
            int attemptNumber,
            SubagentDefinition definition,
            String taskInput,
            ModelProvider provider,
            String providerName,
            VerdictExtractionMode extractionMode) {
        this(taskId, codingRunId, stage, occurrenceKey, attemptNumber,
                definition, taskInput, provider, providerName, extractionMode, null);
    }

    public SubagentRunRequest {
        requireText(taskId, "taskId");
        requireText(codingRunId, "codingRunId");
        requireText(stage, "stage");
        requireText(occurrenceKey, "occurrenceKey");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(taskInput, "taskInput is required");
        Objects.requireNonNull(provider, "provider is required");
        providerName = providerName == null || providerName.isBlank() ? "unknown" : providerName;
        extractionMode = extractionMode == null ? VerdictExtractionMode.AUTO : extractionMode;
        if (extractionMode == VerdictExtractionMode.STATIC_ANALYSIS) {
            throw new IllegalArgumentException(
                    "SubagentRunner model output cannot use STATIC_ANALYSIS extraction mode");
        }
        if (codeTarget != null
                && definition.kind() != SubagentKind.SCOUT
                && definition.kind() != SubagentKind.REVIEWER) {
            throw new IllegalArgumentException(
                    "code evidence targets are only supported for SCOUT or REVIEWER");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
