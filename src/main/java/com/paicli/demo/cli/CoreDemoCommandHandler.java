package com.paicli.demo.cli;

import com.paicli.demo.runtime.HeadlessCoreDemoRunner;
import com.paicli.demo.runtime.CoreDemoRunResult;

import java.io.PrintWriter;
import java.util.Objects;

/** Executes the fixed headless Core Demo command through the production full workflow. */
public final class CoreDemoCommandHandler {
    private final CoreDemoCommandParser parser;
    private final HeadlessCoreDemoRunner runner;
    private final CoreDemoOutput output;

    public CoreDemoCommandHandler(CoreDemoCommandParser parser,
            HeadlessCoreDemoRunner runner, CoreDemoOutput output) {
        this.parser = Objects.requireNonNull(parser);
        this.runner = Objects.requireNonNull(runner);
        this.output = Objects.requireNonNull(output);
    }

    public int execute(String[] args, PrintWriter stdout, PrintWriter stderr) {
        try {
            CoreDemoRunResult result = runner.run(parser.parse(args));
            output.write(result, stdout, stderr);
            return result.exitCode().code();
        } catch (CoreDemoCommandParser.CoreDemoUsageException error) {
            stderr.println(error.getMessage());
            stderr.println("PAICLI_DEMO_RESULT={\"schemaVersion\":1,\"status\":\"USAGE_ERROR\",\"exitCode\":2}");
            stderr.flush();
            return 2;
        } catch (Exception error) {
            stderr.println("Core Demo failed: INTERNAL_ERROR");
            stderr.println("PAICLI_DEMO_RESULT={\"schemaVersion\":1,\"status\":\"INTERNAL_ERROR\",\"exitCode\":8}");
            stderr.flush();
            return 8;
        }
    }
}
