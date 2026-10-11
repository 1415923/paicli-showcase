package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProcessDrainIT {
    @TempDir Path workspace;

    @Test
    void runCommandDrainsLargeOutputInsteadOfDeadlocking() {
        RunCommandTool tool=new RunCommandTool(new SafetyPolicy(workspace,15,4096));
        String command=isWindows()
                ? "for /L %i in (1,1,20000) do @echo 012345678901234567890123456789"
                : "i=0; while [ $i -lt 20000 ]; do echo 012345678901234567890123456789; i=$((i+1)); done";

        var result=tool.execute(new ToolCall("run_command",Map.of("command",command)));
        assertTrue(result.success(),result.error());
        assertTrue(result.output().contains("truncated"));
        assertTrue(result.output().length()<4400);
    }

    @Test
    void runCommandTimeoutIsTerminalAndBounded() {
        RunCommandTool tool=new RunCommandTool(new SafetyPolicy(workspace,1,1024));
        String command=isWindows()?"ping -n 30 127.0.0.1 >NUL":"sleep 30";
        long started=System.nanoTime();
        var result=tool.execute(new ToolCall("run_command",Map.of("command",command)));
        assertFalse(result.success());
        assertTrue(result.error().contains("timed out"));
        assertTrue((System.nanoTime()-started)<10_000_000_000L);
    }

    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
}
