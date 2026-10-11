package com.paicli.worker;

import com.paicli.provider.ModelProvider;

import java.util.Objects;
import java.util.regex.Pattern;

/** Trusted coordinator input for one WorktreeWorker occurrence. */
public record WorktreeWorkerRunRequest(
        String managedWorktreeId,
        String occurrenceKey,
        String taskSpecification,
        String taskItemId,
        ModelProvider provider,
        String providerName,
        WorktreeWorkerConfig config
) {
    public static final int MAX_SPECIFICATION_CHARS = 16_384;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,255}");

    public WorktreeWorkerRunRequest {
        requireSafeId(managedWorktreeId, "managedWorktreeId");
        if (occurrenceKey == null || occurrenceKey.isBlank() || occurrenceKey.length() > 512) {
            throw new IllegalArgumentException("occurrenceKey is required and bounded");
        }
        if (taskSpecification == null || taskSpecification.isBlank()
                || taskSpecification.length() > MAX_SPECIFICATION_CHARS) {
            throw new IllegalArgumentException("taskSpecification is required and bounded");
        }
        if (taskItemId != null) requireSafeId(taskItemId, "taskItemId");
        Objects.requireNonNull(provider, "provider is required");
        if (providerName != null && providerName.length() > 256) {
            throw new IllegalArgumentException("providerName is too large");
        }
        Objects.requireNonNull(config, "config is required");
        if (!config.enabled()) throw new IllegalStateException("WorktreeWorker is disabled");
    }

    private static void requireSafeId(String value, String label) {
        if (value == null || !SAFE_ID.matcher(value).matches()
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(label + " must be a safe identifier");
        }
    }
}
