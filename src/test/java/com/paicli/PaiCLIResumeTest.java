package com.paicli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolCall;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaiCLIResumeTest {
    private static final Instant AT = Instant.parse("2026-07-17T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path workspace;
    private HttpServer loopbackProvider;

    @BeforeEach
    void startLoopbackProvider() throws Exception {
        loopbackProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        loopbackProvider.createContext("/api/chat", exchange -> {
            ObjectNode request = (ObjectNode) MAPPER.readTree(
                    exchange.getRequestBody().readAllBytes());
            boolean hasToolResult = request.path("messages").findValuesAsText("role")
                    .stream().anyMatch("tool"::equals);
            boolean asksToList = request.path("messages").findValuesAsText("content")
                    .stream().anyMatch(value -> value.toLowerCase().contains("list"));
            ObjectNode response = MAPPER.createObjectNode();
            response.put("done", true);
            response.put("done_reason", "stop");
            ObjectNode message = response.putObject("message");
            message.put("role", "assistant");
            if (asksToList && !hasToolResult) {
                message.put("content", "Inspect the workspace once.");
                ObjectNode call = message.putArray("tool_calls").addObject();
                call.put("id", "call_resume_fixture");
                ObjectNode function = call.putObject("function");
                function.put("name", "list_files");
                function.putObject("arguments").put("path", ".");
            } else {
                message.put("content", "{\"userAnswer\":\"Loopback fixture completed.\"}");
            }
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        loopbackProvider.start();
    }

    @AfterEach
    void stopLoopbackProvider() {
        if (loopbackProvider != null) loopbackProvider.stop(0);
    }

    @Test
    void normalRunEnablesStateStore() throws Exception {
        CliOutput output = runCli("list all files");

        assertTrue(output.stderr().isBlank());
        assertTrue(Files.exists(workspace.resolve(".paicli/state/paicli.db")));
        try (StateStore stateStore = new StateStore(workspace)) {
            TaskStore.TaskRecord task = stateStore.tasks.findAll(10).getFirst();
            assertEquals(TaskStore.STATUS_DONE, task.status(),
                    () -> "stdout=" + output.stdout() + " stderr=" + output.stderr());
            assertFalse(stateStore.events.findMessages(task.taskId()).isEmpty());
            var toolCalls=stateStore.toolCalls.findByTask(task.taskId());
            assertEquals(1,toolCalls.size());
            var toolCall=toolCalls.getFirst();
            assertNotNull(toolCall.toolCallId());
            assertTrue(stateStore.events.findMessages(task.taskId()).stream()
                    .filter(message->message.type()==AgentMessage.MessageType.OBSERVATION)
                    .anyMatch(message->toolCall.toolCallId().equals(message.toolCallId())));
            assertTrue(stateStore.checkpoints.loadLatest(task.taskId()).isPresent());
        }
    }

    @Test
    void topLevelResumeBindsThreadWithoutCreatingRunOrOwnership() throws Exception {
        Seed seed = seedRecoverable();
        int before;
        try (StateStore state = new StateStore(workspace)) {
            before = state.r1aDurable.findRunsByTask(seed.task()).size();
        }

        CliOutput output = runCli("resume", seed.thread().value());

        assertTrue(output.stderr().isBlank(), output.stderr());
        assertTrue(output.stdout().contains("Thread: " + seed.thread().value()));
        assertTrue(output.stdout().contains("Run /task recover"));
        try (StateStore state = new StateStore(workspace)) {
            assertEquals(before, state.r1aDurable.findRunsByTask(seed.task()).size());
            assertTrue(state.r1aDurable.readOwnership(seed.task()).isEmpty());
        }
    }

    @Test
    void topLevelResumeLastIsExplicitAndCanonicalTaskShowsMigrationGuidance()
            throws Exception {
        Seed seed = seedRecoverable();

        CliOutput last = runCli("resume", "--last");
        CliOutput task = runCli("resume", seed.task().value());

        assertTrue(last.stdout().contains("Thread: " + seed.thread().value()));
        assertTrue(task.stdout().contains("Task recovery moved to /task recover"));
        try (StateStore state = new StateStore(workspace)) {
            assertEquals(1, state.r1aDurable.findRunsByTask(seed.task()).size());
        }
    }

    @Test
    void cliToolRegistryWiresSnapshotCapableWriteFile() throws Exception {
        String taskId = "cli-write-snapshot";
        try (StateStore stateStore = new StateStore(workspace)) {
            stateStore.tasks.createTask(taskId, "write file", "mock", null,
                    "on-request", 10);
            ToolRegistry registry = PaiCLI.createToolRegistry(
                    new SafetyPolicy(workspace),
                    new FileSnapshotService(workspace, stateStore.snapshots),
                    taskId,
                    new ControlPlanePaths(workspace,
                            List.of("skills", ".paicli/skills"),
                            List.of(".paicli/hooks.json", "custom/hooks.json")));

            var result = registry.invoke(new ToolCall("write_file", Map.of(
                    "path", "cli-created.txt",
                    "content", "created through CLI registry")));

            assertTrue(result.success());
            FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots
                    .findByTask(taskId).getFirst();
            assertEquals(FileSnapshotStore.OPERATION_CREATE, snapshot.operation());
            assertEquals(FileSnapshotStore.STATUS_APPLIED, snapshot.status());
        }
    }

    @Test
    void sessionArtifactsUseSanitizedStateValues() throws Exception {
        String sensitiveValue = "synthetic-cli-secret";
        CliOutput output = runCli("apiKey=" + sensitiveValue);

        assertTrue(output.stderr().isBlank());
        Path trace;
        try (var files = Files.list(workspace.resolve(".paicli/sessions"))) {
            trace = files.findFirst().orElseThrow();
        }
        Path report;
        try (var files = Files.list(workspace.resolve("reports/runs"))) {
            report = files.findFirst().orElseThrow();
        }
        String persistedArtifacts = Files.readString(trace) + Files.readString(report);
        assertFalse(persistedArtifacts.contains(sensitiveValue));
        assertTrue(persistedArtifacts.contains("<redacted>"));
    }

    private Seed seedRecoverable() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Config config = config();
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            var root = new InstructionManifestRuntime(state, workspace).admitRoot(
                    task, "resume me", 10_000, config, RuntimeManifest.disabled(), AT);
            state.checkpoints.save(task.value(), 1, "RUNNING",
                    List.of(AgentMessage.userGoal("resume me")));
            return new Seed(task, root.threadId());
        }
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model-a", "", "", "system", 10,
                "verify", 120, 50_000, 1, false, true, false);
    }

    private CliOutput runCli(String... args) throws Exception {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        InputStream originalIn = System.in;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Path configDir = workspace.resolve(".paicli");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), MAPPER.writeValueAsString(Map.of(
                "enable_plan", false,
                "provider", "ollama",
                "model", "loopback-test-model",
                "base_url", "http://127.0.0.1:"
                        + loopbackProvider.getAddress().getPort(),
                "context_window_tokens", 40960)));
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.dir", workspace.toString());
            System.setProperty("user.home", workspace.toString());
            System.setIn(new ByteArrayInputStream(new byte[0]));
            System.setOut(out);
            System.setErr(err);
            PaiCLI.run(args);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
        return new CliOutput(stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Seed(CanonicalId task, CanonicalId thread) { }
    private record CliOutput(String stdout, String stderr) { }
}
