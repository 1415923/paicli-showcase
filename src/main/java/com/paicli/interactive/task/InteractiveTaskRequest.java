package com.paicli.interactive.task;

import java.nio.file.Path;
import java.util.List;

public record InteractiveTaskRequest(
        String sessionId, Path workspace, String input, List<String> fileReferences) {
    public InteractiveTaskRequest {
        if (sessionId == null || sessionId.isBlank() || workspace == null
                || input == null || input.isBlank() || input.length() > 8192
                || input.chars().anyMatch(value -> value < 0x20 && value != '\n' && value != '\t')
                || fileReferences == null) {
            throw new IllegalArgumentException("invalid interactive task request");
        }
        workspace = workspace.toAbsolutePath().normalize();
        fileReferences = List.copyOf(fileReferences);
    }
}