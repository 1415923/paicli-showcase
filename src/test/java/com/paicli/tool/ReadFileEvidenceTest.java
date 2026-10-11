package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReadFileEvidenceTest {
    @TempDir Path workspace;

    @Test void numberedReadPreservesBlankLinesAndRawDefault() throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "first\r\n\r\n第三行\r\nlast");
        var tool = new ReadFileTool(new SafetyPolicy(workspace));
        var raw = tool.execute(new ToolCall("read_file", Map.of("path", "source.txt")));
        assertEquals("first\n\n第三行\nlast", raw.output());
        var numbered = tool.execute(new ToolCall("read_file", Map.of("path", "source.txt", "line_numbers", true)));
        assertTrue(numbered.success(), numbered.error());
        assertEquals("1: first\n2: \n3: 第三行\n4: last", numbered.output());
        var limited = tool.execute(new ToolCall("read_file", Map.of("path", "source.txt", "line_numbers", true, "max_lines", 3)));
        assertEquals("1: first\n2: \n3: 第三行", limited.output());
    }

    @Test void displayOptionCannotRelaxControlDirectoryOrPathBoundary() throws Exception {
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/private.txt"), "PRIVATE");
        var tool = new ReadFileTool(new SafetyPolicy(workspace));
        for (String path : new String[]{".paicli/private.txt", "../outside.txt"}) {
            var call = new ToolCall("read_file", Map.of("path", path, "line_numbers", true));
            assertFalse(tool.preflightCall(call).success());
            assertFalse(tool.execute(call).success());
        }
        assertFalse(tool.execute(new ToolCall("read_file", Map.of("path", "x", "line_numbers", "true"))).success());
    }
}
