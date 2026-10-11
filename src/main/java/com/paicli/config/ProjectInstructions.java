package com.paicli.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads project-level instruction files (PAICLI.md, AGENTS.md) from the workspace.
 * Missing files are silently skipped. Content is truncated to a configured maximum.
 */
public record ProjectInstructions(
        String content,
        List<String> sources,
        int originalLength,
        int truncatedTo
) {

    private static final List<String> INSTRUCTION_FILES = List.of("PAICLI.md", "AGENTS.md");

    /**
     * Load project instructions from the workspace directory.
     *
     * @param workspace       the workspace root
     * @param maxChars        maximum total characters to include (0 = no limit)
     * @return loaded instructions (never null; content may be empty)
     */
    public static ProjectInstructions load(Path workspace, int maxChars) {
        StringBuilder sb = new StringBuilder();
        List<String> sources = new ArrayList<>();

        for (String filename : INSTRUCTION_FILES) {
            Path file = workspace.resolve(filename);
            if (Files.exists(file) && Files.isRegularFile(file)) {
                try {
                    String fileContent = Files.readString(file, StandardCharsets.UTF_8);
                    if (!fileContent.isBlank()) {
                        if (!sb.isEmpty()) {
                            sb.append("\n\n");
                        }
                        sb.append("--- ").append(filename).append(" ---\n");
                        sb.append(fileContent.trim());
                        sources.add(filename);
                    }
                } catch (IOException e) {
                    // Silently skip unreadable files
                }
            }
        }

        String fullContent = sb.toString();
        int originalLength = fullContent.length();

        if (maxChars > 0 && fullContent.length() > maxChars) {
            fullContent = fullContent.substring(0, maxChars);
        }

        return new ProjectInstructions(fullContent, sources, originalLength, maxChars);
    }

    /**
     * Load with default max chars from config.
     */
    public static ProjectInstructions load(Path workspace, Config config) {
        return load(workspace, config.maxProjectInstructionChars());
    }

    public boolean isEmpty() {
        return content == null || content.isBlank();
    }

    public boolean wasTruncated() {
        return truncatedTo > 0 && originalLength > truncatedTo;
    }
}
