package com.paicli.demo.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.demo.runtime.CoreDemoRunResult;

import java.io.PrintWriter;
import java.util.LinkedHashMap;

public final class CoreDemoOutput {
    private static final int MAX_ERROR = 1024;
    private final ObjectMapper json = new ObjectMapper();

    public void write(CoreDemoRunResult result, PrintWriter stdout, PrintWriter stderr) {
        PrintWriter target = result.exitCode().code() == 0 ? stdout : stderr;
        target.println("PaiCLI Core Demo");
        target.println("scenario: " + result.scenarioId());
        target.println("run: " + result.runId());
        target.println("status: " + result.status());
        target.println("exitCode: " + result.exitCode().code());
        if (result.artifactLocation() != null) {
            target.println("artifacts: " + result.artifactLocation());
        }
        if (result.errorCategory() != null) {
            target.println("error: " + bound(result.errorCategory()));
        }
        LinkedHashMap<String, Object> machine = new LinkedHashMap<>();
        machine.put("schemaVersion", 1);
        machine.put("runId", result.runId());
        machine.put("status", result.status());
        machine.put("exitCode", result.exitCode().code());
        try {
            target.println("PAICLI_DEMO_RESULT=" + json.writeValueAsString(machine));
        } catch (Exception e) {
            target.println("PAICLI_DEMO_RESULT={\"schemaVersion\":1,\"status\":\"OUTPUT_ERROR\",\"exitCode\":8}");
        }
        target.flush();
    }

    private static String bound(String value) {
        String safe = value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
        return safe.substring(0, Math.min(MAX_ERROR, safe.length()));
    }
}
