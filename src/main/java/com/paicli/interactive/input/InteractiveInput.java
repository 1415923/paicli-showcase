package com.paicli.interactive.input;

import java.util.List;

public record InteractiveInput(Kind kind, String text, List<String> fileReferences) {
    public enum Kind { EMPTY, SLASH_COMMAND, TASK }

    public InteractiveInput {
        if (kind == null || text == null || fileReferences == null) {
            throw new IllegalArgumentException("interactive input fields are required");
        }
        fileReferences = List.copyOf(fileReferences);
    }
}
