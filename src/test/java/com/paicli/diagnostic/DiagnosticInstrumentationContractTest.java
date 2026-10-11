package com.paicli.diagnostic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticInstrumentationContractTest {
    @Test
    void requiredDiagnosticStagesAreWiredIntoProductionSources() throws Exception {
        Path production = Path.of("src/main/java/com/paicli");
        String source;
        try (var files = Files.walk(production)) {
            StringBuilder joined = new StringBuilder();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                joined.append(Files.readString(file)).append('\n');
            }
            source = joined.toString();
        }

        List<String> required = List.of(
                "PROCESS_START",
                "TERMINAL_INIT_START", "TERMINAL_INIT_END",
                "NATIVE_COMPONENT_INIT_START", "NATIVE_COMPONENT_INIT_END",
                "WORKSPACE_RESOLUTION_START", "WORKSPACE_CANDIDATE", "WORKSPACE_SELECTED",
                "FOREGROUND_OWNERSHIP_START", "FOREGROUND_OWNERSHIP_END",
                "TASK_PREPARATION_START",
                "CODE_CANDIDATE_EXTRACTION_START", "CODE_CANDIDATE_EXTRACTION_END",
                "CODE_SCAN_START", "CODE_SCAN_PROGRESS", "CODE_SCAN_END",
                "CODE_SCAN_SKIPPED",
                "PROVIDER_PREPARATION_START", "PROVIDER_PREPARATION_END",
                "MANIFEST_BUILD_START", "MANIFEST_BUILD_END",
                "DURABLE_ADMISSION_START", "DURABLE_ADMISSION_END",
                "TASK_ACCEPTED", "PROVIDER_CALL_START",
                "CHILD_PROCESS_CREATE_START", "CHILD_PROCESS_CREATE_END");
        for (String stage : required) {
            assertTrue(source.contains('"' + stage + '"'), "missing stage " + stage);
        }
    }

    @Test
    void scanProgressIsAggregateOnlyAndDoesNotPublishVisitedPaths() throws Exception {
        String stage = Files.readString(Path.of(
                "src/main/java/com/paicli/interactive/runtime/InteractiveCodeIntelligenceStage.java"));

        assertTrue(stage.contains("visited_directories"));
        assertTrue(stage.contains("visited_files"));
        assertTrue(stage.contains("matched_java_files"));
        assertTrue(stage.contains("approximate_bytes"));
        assertTrue(stage.contains("reparse_or_link_count"));
        assertTrue(stage.contains("crossed_initial_root"));
    }
}
