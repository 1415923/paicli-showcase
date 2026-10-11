package com.paicli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.identity.CanonicalId;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCLICodingRuntimeIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STRUCTURED_PLAN = """
            {"acceptanceCriteria":["The workspace is inspected"],
             "assumptions":[],"risks":[],
             "verificationStrategy":"Use the configured verification command when present.",
             "steps":[{"title":"Inspect workspace",
               "definition":"List the workspace files once.","dependsOn":[],
               "completionRule":"ALL_REQUIRED_EVIDENCE",
               "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
               "toolRequirements":[{"requirementId":"list-workspace","requirementOrder":0,
                 "toolName":"list_files","effectClass":"READ",
                 "authoritySelector":{"kind":"EXACT_PATH","path":".","includeInternal":false},
                 "minSuccesses":1,"maxOccurrences":1,
                 "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;

    @TempDir
    Path workspace;
    private HttpServer loopbackProvider;
    private final AtomicLong toolCallSequence = new AtomicLong();
    private final List<String> providerOperations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startLoopbackProvider() throws Exception {
        providerOperations.clear();
        loopbackProvider = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        loopbackProvider.createContext("/api/chat", exchange -> {
            ObjectNode request = (ObjectNode) MAPPER.readTree(
                    exchange.getRequestBody().readAllBytes());
            ObjectNode response = MAPPER.createObjectNode();
            response.put("done", true);
            response.put("done_reason", "stop");
            ObjectNode message = response.putObject("message");
            message.put("role", "assistant");
            boolean planning = !request.path("tools").isArray()
                    || request.path("tools").isEmpty();
            providerOperations.add(planning ? CodingRunStore.PHASE_PLANNING
                    : CodingRunStore.PHASE_EXECUTING);
            if (planning) {
                message.put("content", STRUCTURED_PLAN);
            } else if (containsToolResult(request)) {
                message.put("content", "{\"userAnswer\":\"Loopback fixture completed.\"}");
            } else {
                message.put("content", "Inspect the workspace once.");
                ObjectNode call = message.putArray("tool_calls").addObject();
                call.put("id", "call_coding_runtime_"
                        + toolCallSequence.incrementAndGet());
                ObjectNode function = call.putObject("function");
                function.put("name", "list_files");
                function.putObject("arguments").put("path", ".");
            }
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        loopbackProvider.start();
    }

    @AfterEach
    void stopLoopbackProvider() {
        if (loopbackProvider != null) {
            loopbackProvider.stop(0);
        }
    }

    @Test
    void structuredPlanFailsClosedWithoutInteractiveApproval() throws Exception {
        writeConfig(workspace, "{\"enable_plan\":true}");
        CliOutput output = runCli("list all files");

        assertTrue(!output.stderr().contains("Error:"), output.stderr());
        try (StateStore stateStore = new StateStore(workspace)) {
            TaskStore.TaskRecord task = stateStore.tasks.findAll(10).getFirst();
            CodingRunStore.CodingRunRecord run =
                    stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertEquals(TaskStore.STATUS_FAILED, task.status(),
                    () -> "taskError=" + task.lastError()
                            + ",runError=" + run.lastError()
                            + ",stdout=" + output.stdout()
                            + ",stderr=" + output.stderr());
            assertEquals(CodingRunStore.KIND_INITIAL, run.runKind());
            assertEquals(CodingRunStore.PHASE_FAILED, run.phase());
            assertEquals("PLAN_APPROVAL_REQUIRED", task.lastError());
            assertEquals("PLAN_APPROVAL_REQUIRED", run.lastError());
            assertTrue(stateStore.verifications.findByRun(run.runId()).isEmpty());
        }
    }

    @Test
    void verifyOptionRecordsPassed() throws Exception {
        CliOutput output = runCli(
                "--no-plan", "--verify", passCommand(), "list all files");

        try (StateStore stateStore = new StateStore(workspace)) {
            VerificationFacts facts = verificationFacts(stateStore, output, workspace);
            assertEquals(0, output.exitCode(), facts::safeSummary);
            assertEquals(1, facts.taskCount(), facts::safeSummary);
            assertEquals(1, facts.runCount(), facts::safeSummary);
            assertEquals(TaskStore.STATUS_DONE, facts.taskStatus(), facts::safeSummary);
            assertEquals(CodingRunStore.PHASE_COMPLETED,
                    facts.runPhase(), facts::safeSummary);
            assertEquals(1, facts.verificationCount(), facts::safeSummary);
            assertEquals(facts.taskId(), facts.verificationTaskId(), facts::safeSummary);
            assertEquals(facts.runId(), facts.verificationRunId(), facts::safeSummary);
            assertEquals(VerificationStore.STATUS_PASSED,
                    facts.verificationStatus(), facts::safeSummary);
        }
    }

    @Test
    void verificationFixtureUsesIsolatedWorkspaceAndDatabase() throws Exception {
        Path secondWorkspace = Files.createDirectory(workspace.resolve("second-workspace"));

        CliOutput first = runCli(
                "--no-plan", "--verify", passCommand(), "list all files");
        CliOutput second = runCliIn(secondWorkspace,
                "--no-plan", "--verify", passCommand(), "list all files");

        assertEquals(0, first.exitCode());
        assertEquals(0, second.exitCode());
        Path firstDatabase = databasePath(workspace);
        Path secondDatabase = databasePath(secondWorkspace);
        assertNotEquals(firstDatabase, secondDatabase);
        assertTrue(Files.isRegularFile(firstDatabase));
        assertTrue(Files.isRegularFile(secondDatabase));
        try (StateStore firstState = new StateStore(workspace);
             StateStore secondState = new StateStore(secondWorkspace)) {
            String firstTask = firstState.tasks.findAll(10).getFirst().taskId();
            String secondTask = secondState.tasks.findAll(10).getFirst().taskId();
            assertNotEquals(firstTask, secondTask);
            assertEquals(1, firstState.verifications.findLatestByTask(firstTask).stream().count());
            assertEquals(1, secondState.verifications.findLatestByTask(secondTask).stream().count());
        }
    }

    @Test
    void verificationFixtureRestoresSystemPropertiesAndGlobalStreams() throws Exception {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        CliOutput output = runCli("--no-plan", "list all files");

        assertEquals(0, output.exitCode());
        assertEquals(originalDir, System.getProperty("user.dir"));
        assertEquals(originalHome, System.getProperty("user.home"));
        assertSame(originalOut, System.out);
        assertSame(originalErr, System.err);
    }

    @Test
    void verifyCommandAliasRecordsPassed() throws Exception {
        CliOutput output = runCli(
                "--no-plan", "--verify-command", passCommand(), "list all files");

        assertTrue(!output.stderr().contains("Error:"), output.stderr());
        try (StateStore stateStore = new StateStore(workspace)) {
            String taskId = stateStore.tasks.findAll(10).getFirst().taskId();
            String runId = stateStore.codingRuns.findByTask(taskId).getFirst().runId();
            assertEquals(VerificationStore.STATUS_PASSED,
                    stateStore.verifications.findByRun(runId).getFirst().status());
        }
    }

    @Test
    void failedVerificationUsesConfiguredRepairLimit() throws Exception {
        CliOutput output = runCli(
                "--no-plan",
                "--verify", failCommand(),
                "--max-repair-attempts", "2",
                "list all files");

        assertTrue(!output.stderr().contains("Error:"), output.stderr());
        try (StateStore stateStore = new StateStore(workspace)) {
            TaskStore.TaskRecord task = stateStore.tasks.findAll(10).getFirst();
            CodingRunStore.CodingRunRecord run =
                    stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertEquals(TaskStore.STATUS_FAILED, task.status());
            assertEquals(CodingRunStore.PHASE_FAILED, run.phase());
            assertEquals(2, run.repairAttemptsCompleted(),
                    () -> "taskError=" + task.lastError()
                            + ",runError=" + run.lastError()
                            + ",stdout=" + output.stdout()
                            + ",stderr=" + output.stderr());
            assertEquals(2, stateStore.repairAttempts.findByRun(run.runId()).size());
            assertEquals(3, stateStore.verifications.findByRun(run.runId()).size());
        }
    }

    @Test
    void noRepairStopsAfterInitialFailedVerification() throws Exception {
        CliOutput output = runCli(
                "--no-plan", "--verify", failCommand(),
                "--no-repair", "list all files");

        assertTrue(!output.stderr().contains("Error:"), output.stderr());
        try (StateStore stateStore = new StateStore(workspace)) {
            TaskStore.TaskRecord task = stateStore.tasks.findAll(10).getFirst();
            CodingRunStore.CodingRunRecord run =
                    stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertEquals(TaskStore.STATUS_FAILED, task.status());
            assertEquals(0, run.repairAttemptsCompleted());
            assertTrue(stateStore.repairAttempts.findByRun(run.runId()).isEmpty());
            assertEquals(1, stateStore.verifications.findByRun(run.runId()).size(),
                    () -> "taskError=" + task.lastError()
                            + ",runError=" + run.lastError()
                            + ",stdout=" + output.stdout()
                            + ",stderr=" + output.stderr());
        }
    }

    @Test
    void noPlanSkipsPlanPersistence() throws Exception {
        CliOutput output = runCli("--no-plan", "list all files");

        assertTrue(!output.stderr().contains("Error:"), output.stderr());
        try (StateStore stateStore = new StateStore(workspace)) {
            String taskId = stateStore.tasks.findAll(10).getFirst().taskId();
            String runId = stateStore.codingRuns.findByTask(taskId).getFirst().runId();
            assertTrue(stateStore.plans.findByRun(runId).isEmpty());
        }
    }

    @Test
    void repairLimitOutsideRangeIsRejectedBeforeStateStoreStarts() throws Exception {
        CliOutput output = runCli(
                "--max-repair-attempts", "6", "list all files");

        assertTrue(output.stderr().contains(
                "--max-repair-attempts must be between 1 and 5"));
        assertTrue(output.stdout().contains("paicli --max-repair-attempts"));
        assertTrue(java.nio.file.Files.notExists(
                workspace.resolve(".paicli/state/paicli.db")));
    }

    @Test
    void explicitSkillsSelectAndReportWhileNoSkillsDisablesAllSkillActivity()
            throws Exception {
        writeSkill("skills/java/SKILL.md", "java-list", "Java list support",
                "list", "Skill body Authorization: Bearer hidden-skill-token");

        CliOutput enabled = runCli("use $java-list to list all files");
        assertTrue(enabled.stderr().isBlank(), enabled.stderr());
        assertTrue(enabled.stdout().contains("Skills     : java-list"));
        try (StateStore stateStore = new StateStore(workspace)) {
            var task = stateStore.tasks.findAll(10).getFirst();
            var run = stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertTrue(stateStore.skillLoads.findByRun(run.runId()).size() >= 1);
            assertTrue(stateStore.skillSelections.findSelectedByRun(run.runId()).stream()
                    .anyMatch(selected -> "java-list".equals(selected.skillName())));
            assertTrue(run.skillsManifestSha256() != null);
        }
        String report = readReport(workspace);
        assertTrue(report.contains("Selected skills: java-list"));
        assertFalse(report.contains("hidden-skill-token"));

        Path disabledWorkspace = Files.createDirectory(workspace.resolve("disabled"));
        copySkillTo(disabledWorkspace);
        CliOutput disabled = runCliIn(disabledWorkspace, "--no-skills", "list all files");
        assertTrue(disabled.stderr().isBlank(), disabled.stderr());
        assertTrue(disabled.stdout().contains("Skills     : none"));
        try (StateStore stateStore = new StateStore(disabledWorkspace)) {
            var task = stateStore.tasks.findAll(10).getFirst();
            var run = stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertTrue(stateStore.skillLoads.findByRun(run.runId()).isEmpty());
            assertTrue(stateStore.skillSelections.findSelectionsByRun(run.runId()).isEmpty());
            assertTrue(run.skillsManifestSha256() != null);
        }

        Path overrideWorkspace = Files.createDirectory(workspace.resolve("enabled-skills"));
        Path overrideSkill = overrideWorkspace.resolve("skills/java/SKILL.md");
        Files.createDirectories(overrideSkill.getParent());
        Files.copy(workspace.resolve("skills/java/SKILL.md"), overrideSkill);
        writeConfig(overrideWorkspace, "{\"enable_skills\":false}");
        CliOutput overridden = runCliIn(
                overrideWorkspace, "--skills", "use $java-list to list all files");
        assertTrue(overridden.stderr().isBlank(), overridden.stderr());
        try (StateStore stateStore = new StateStore(overrideWorkspace)) {
            var task = stateStore.tasks.findAll(10).getFirst();
            var run = stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertTrue(stateStore.skillSelections.findSelectedByRun(run.runId()).stream()
                    .anyMatch(selected -> "java-list".equals(selected.skillName())));
        }
    }

    @Test
    void hooksCliEnableDisableAndCustomConfigControlExecution() throws Exception {
        writePlanlessHookPair(workspace.resolve(".paicli/hooks.json"));

        CliOutput planless = runCli("--hooks", "list all files");
        assertTrue(planless.stderr().isBlank(), planless.stderr());
        assertFalse(providerOperations.isEmpty());
        assertEquals(CodingRunStore.PHASE_EXECUTING, providerOperations.getFirst());
        assertEquals(0, providerOperations.stream()
                .filter(CodingRunStore.PHASE_PLANNING::equals).count());
        try (StateStore stateStore = new StateStore(workspace)) {
            var task = stateStore.tasks.findAll(10).getFirst();
            var run = stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            var hookRuns = stateStore.hookRuns.findByRun(run.runId());
            assertEquals(1, hookRuns.size());
            assertEquals("planless-before-execute", hookRuns.getFirst().hookId());
            assertTrue(stateStore.plans.findByRun(run.runId()).isEmpty());
            assertTrue(stateStore.contexts.pointer(
                    task.taskId(), run.runId(), OperationKind.PLAN).isEmpty());
            var result = stateStore.userResults.find(CanonicalId.parse(task.taskId()),
                    CanonicalId.parse(run.runId())).orElseThrow();
            assertEquals(null, result.planRevisionId());
            assertTrue(run.hooksManifestSha256() != null);
        }

        Path enabledWorkspace = Files.createDirectory(workspace.resolve("enabled-hooks"));
        writeHooks(enabledWorkspace.resolve("custom/hooks.json"), "custom-hook");
        writeConfig(enabledWorkspace, "{\"enable_plan\":true}");
        CliOutput enabled = runCliIn(enabledWorkspace,
                "--hooks", "--hooks-config", "custom/hooks.json", "list all files");
        assertTrue(enabled.stderr().isBlank(), enabled.stderr());
        assertTrue(enabled.stdout().contains("Hook runs  : 1"));
        try (StateStore stateStore = new StateStore(enabledWorkspace)) {
            var task = stateStore.tasks.findAll(10).getFirst();
            var run = stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertEquals("custom-hook", stateStore.hookRuns
                    .findByRun(run.runId()).getFirst().hookId());
            assertTrue(run.hooksManifestSha256() != null);
        }
        String report = readReport(enabledWorkspace);
        assertTrue(report.contains("custom-hook=PASSED"));
        assertFalse(report.contains("hook-output-secret"));

        Path overrideWorkspace = Files.createDirectory(workspace.resolve("disabled-hooks"));
        writeHooks(overrideWorkspace.resolve(".paicli/hooks.json"), "disabled-hook");
        writeConfig(overrideWorkspace, "{\"enable_hooks\":true}");
        CliOutput overridden = runCliIn(
                overrideWorkspace, "--no-hooks", "list all files");
        assertTrue(overridden.stderr().isBlank(), overridden.stderr());
        try (StateStore stateStore = new StateStore(overrideWorkspace)) {
            var task = stateStore.tasks.findAll(10).getFirst();
            var run = stateStore.codingRuns.findByTask(task.taskId()).getFirst();
            assertTrue(stateStore.hookRuns.findByRun(run.runId()).isEmpty());
            assertTrue(run.hooksManifestSha256() != null);
        }
    }

    private String passCommand() {
        return isWindows() ? "exit 0" : "true";
    }

    private String failCommand() {
        return "exit 7";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    private CliOutput runCli(String... args) throws Exception {
        return runCliIn(workspace, args);
    }

    private CliOutput runCliIn(Path runWorkspace, String... args) throws Exception {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(
                stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(
                     stderr, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.dir", runWorkspace.toString());
            System.setProperty("user.home", runWorkspace.toString());
            System.setOut(out);
            System.setErr(err);
            writeLoopbackProviderConfig(runWorkspace);
            exitCode = PaiCLI.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
        return new CliOutput(exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private VerificationFacts verificationFacts(StateStore stateStore,
                                                 CliOutput output,
                                                 Path runWorkspace) throws SQLException {
        List<TaskStore.TaskRecord> tasks = stateStore.tasks.findAll(10);
        TaskStore.TaskRecord task = tasks.size() == 1 ? tasks.getFirst() : null;
        List<CodingRunStore.CodingRunRecord> runs = task == null
                ? List.of() : stateStore.codingRuns.findByTask(task.taskId());
        CodingRunStore.CodingRunRecord run = runs.size() == 1 ? runs.getFirst() : null;
        List<VerificationStore.VerificationRecord> verifications = run == null
                ? List.of() : stateStore.verifications.findByRun(run.runId());
        VerificationStore.VerificationRecord verification = verifications.size() == 1
                ? verifications.getFirst() : null;
        return new VerificationFacts(output.exitCode(), tasks.size(),
                task == null ? null : task.taskId(), task == null ? null : task.status(),
                runs.size(), run == null ? null : run.runId(),
                run == null ? null : run.phase(), verifications.size(),
                verification == null ? null : verification.taskId(),
                verification == null ? null : verification.runId(),
                verification == null ? null : verification.status(),
                runWorkspace.toAbsolutePath().normalize(), databasePath(runWorkspace));
    }

    private static Path databasePath(Path runWorkspace) {
        return runWorkspace.toAbsolutePath().normalize()
                .resolve(".paicli/state/paicli.db");
    }

    private void writeLoopbackProviderConfig(Path runWorkspace)
            throws Exception {
        Path config = runWorkspace.resolve(".paicli/config.json");
        Files.createDirectories(config.getParent());
        ObjectNode root = Files.isRegularFile(config)
                ? (ObjectNode) MAPPER.readTree(Files.readString(config))
                : MAPPER.createObjectNode();
        root.put("provider", "ollama");
        root.put("model", "loopback-test-model");
        root.put("base_url", "http://127.0.0.1:"
                + loopbackProvider.getAddress().getPort());
        root.put("context_window_tokens", 40_960);
        Files.writeString(config, MAPPER.writeValueAsString(root));
    }

    private static boolean containsToolResult(ObjectNode request) {
        var messages = request.path("messages");
        if (!messages.isArray()) {
            return false;
        }
        for (var message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    private void writeSkill(String relativePath, String name,
                            String description, String trigger, String body)
            throws Exception {
        Path path = workspace.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "---\nname: " + name + "\ndescription: "
                + description + "\ntriggers: [" + trigger + "]\n---\n" + body);
    }

    private void copySkillTo(Path targetWorkspace) throws Exception {
        Path target = targetWorkspace.resolve("skills/java/SKILL.md");
        Files.createDirectories(target.getParent());
        Files.copy(workspace.resolve("skills/java/SKILL.md"), target);
    }

    private void writeHooks(Path path, String hookId) throws Exception {
        writeHooks(path, hookId, "BEFORE_PLAN");
    }

    private void writeHooks(Path path, String hookId, String event) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {"version":1,"hooks":[
                  {"id":"%s","event":"%s",
                   "command":"echo hook-output-secret","failure_policy":"warn"}
                ]}
                """.formatted(hookId, event));
    }

    private void writePlanlessHookPair(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {"version":1,"hooks":[
                  {"id":"planless-before-plan","event":"BEFORE_PLAN",
                   "command":"echo hook-output-secret","failure_policy":"warn"},
                  {"id":"planless-before-execute","event":"BEFORE_EXECUTE",
                   "command":"echo hook-output-secret","failure_policy":"warn"}
                ]}
                """);
    }

    private void writeConfig(Path targetWorkspace, String json) throws Exception {
        Path config = targetWorkspace.resolve(".paicli/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, json);
    }

    private String readReport(Path targetWorkspace) throws Exception {
        try (Stream<Path> reports = Files.list(
                targetWorkspace.resolve("reports/runs"))) {
            return Files.readString(reports.findFirst().orElseThrow());
        }
    }

    private record CliOutput(int exitCode, String stdout, String stderr) {
    }

    private record VerificationFacts(
            int exitCode, int taskCount, String taskId, String taskStatus,
            int runCount, String runId, String runPhase, int verificationCount,
            String verificationTaskId, String verificationRunId,
            String verificationStatus, Path workspace, Path database) {
        private String safeSummary() {
            return "verificationFacts{exitCode=" + exitCode
                    + ",taskCount=" + taskCount + ",taskId=" + taskId
                    + ",taskStatus=" + taskStatus + ",runCount=" + runCount
                    + ",runId=" + runId + ",runPhase=" + runPhase
                    + ",verificationCount=" + verificationCount
                    + ",verificationTaskId=" + verificationTaskId
                    + ",verificationRunId=" + verificationRunId
                    + ",verificationStatus=" + verificationStatus
                    + ",workspace=" + workspace + ",database=" + database + "}";
        }
    }
}
