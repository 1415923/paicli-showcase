package com.paicli.benchmark;

import com.paicli.safety.SafetyPolicy;
import com.paicli.tool.GitDiffTool;
import com.paicli.tool.GrepTool;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.RunCommandTool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.WriteFileTool;

import java.nio.file.Path;

/** The same six public Tool definitions and safety policy, without snapshot durability. */
public final class BenchmarkToolRegistryFactory {
    private BenchmarkToolRegistryFactory() { }

    public static ToolRegistry create(Path workspace) {
        SafetyPolicy safety = new SafetyPolicy(workspace);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(safety));
        registry.register(new ReadFileTool(safety));
        registry.register(new GrepTool(safety));
        registry.register(new RunCommandTool(safety));
        registry.register(new GitDiffTool(safety));
        registry.register(new WriteFileTool(safety));
        return registry;
    }
}
