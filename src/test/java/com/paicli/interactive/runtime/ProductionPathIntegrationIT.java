package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventType;
import com.paicli.identity.CanonicalId;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.provider.OpenAICompatiblePreset;
import com.paicli.provider.OpenAICompatibleProvider;
import com.paicli.state.StateStore;
import com.paicli.state.CodingRunStore;
import com.paicli.state.DatabaseManager;
import com.paicli.state.InteractiveProposalStore;
import com.paicli.state.PlanCompletionModels;
import com.paicli.state.PlanExecutionModels;
import com.paicli.state.TaskStore;
import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.interactive.command.SlashCommandRegistry;
import com.paicli.interactive.command.SlashCommandRouter;
import com.paicli.interactive.completion.WorkspacePathGuard;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.input.InteractiveInputParser;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.render.InteractiveShellRenderer;
import com.paicli.interactive.render.PlainRuntimeEventRenderer;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.shell.InteractivePromptRenderer;
import com.paicli.interactive.shell.InteractiveShell;
import com.paicli.interactive.shell.InteractiveShellConfig;
import com.paicli.interactive.shell.ShellInputReader;
import com.paicli.interactive.task.TaskSubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

class ProductionPathIntegrationIT {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void deepSeekThinkingToolContinuationCompletesDurableTask()throws Exception{
        Path workspace=Files.createDirectories(temp.resolve("deepseek-thinking-workspace"));
        Files.writeString(workspace.resolve("README.md"),"PaiCLI thinking continuation fixture.\n");
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        AtomicInteger calls=new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean toolEmitted=
                new java.util.concurrent.atomic.AtomicBoolean();
        List<com.fasterxml.jackson.databind.JsonNode> requests=new CopyOnWriteArrayList<>();
        server.createContext("/chat/completions",exchange->{
            var body=JSON.readTree(exchange.getRequestBody());requests.add(body);
            calls.incrementAndGet();
            boolean continuation=java.util.stream.StreamSupport.stream(
                    body.path("messages").spliterator(),false)
                    .anyMatch(message->message.has("tool_call_id"));
            String response;
            if(body.has("response_format"))response=response(readPlan().content());
            else if(!toolEmitted.getAndSet(true))response="""
                        {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                        "content":"",
                        "reasoning_content":"private-e2e-reasoning-sentinel",
                        "tool_calls":[{"id":"deepseek-e2e-call","type":"function",
                        "function":{"name":"list_files","arguments":"{\\"path\\":\\".\\"}"}}]}}]}
                        """;
            else if(continuation)response=response(JSON.writeValueAsString(Map.of(
                    "userAnswer","README.md is the thinking continuation fixture. No files were changed.")));
            else response=response(JSON.writeValueAsString(Map.of(
                    "userAnswer","README.md is the thinking continuation fixture. No files were changed.")));
            byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();
        String completedTaskId;
        try{
            Config config=Config.defaults().withProvider(Config.PROVIDER_OPENAI,
                    "deepseek-v4-flash","http://localhost:"+server.getAddress().getPort(),
                    "DEEPSEEK_API_KEY").withProviderPreset("deepseek")
                    .withThinking("enabled","max").withApprovalMode(Config.MODE_READ_ONLY);
            assertFalse(config.isPlanEnabled());
            OpenAICompatibleProvider raw=new OpenAICompatibleProvider(config.baseUrl(),
                    "test-secret",config.model(),config.effectiveSystemPrompt(),5,
                    OpenAICompatiblePreset.from(config),config.effectiveThinking(),
                    config.effectiveReasoningEffort());
            ProductionInteractiveTaskExecutor executor=new ProductionInteractiveTaskExecutor(
                    workspace,()->config,ignored->raw);
            StringWriter transcript=new StringWriter();
            PlainRuntimeEventRenderer events=new PlainRuntimeEventRenderer(
                    new PrintWriter(transcript));
            try(InteractiveRuntimeAdapter runtime=InteractiveRuntimeAdapter.open(
                    workspace,executor,events)){
                WaitingReader input=new WaitingReader(runtime,"List files without modifying them");
                CaptureShellRenderer renderer=new CaptureShellRenderer();
                InteractiveShell shell=new InteractiveShell(new InteractiveShellConfig(
                        "1.2.0",workspace,Config.PROVIDER_OPENAI,"deepseek-v4-flash",
                        Config.MODE_READ_ONLY,InteractiveSession.TerminalMode.PLAIN),input,renderer,
                        new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                        router(workspace,runtime),runtime,
                        new InteractiveHistoryStore(temp.resolve("deepseek-history")),
                        new InteractivePromptRenderer(),Clock.systemUTC());
                assertEquals(1,shell.run().submittedTasks());
                input.assertPlanlessApprovalFlow();
                var terminal=runtime.lastResult().orElseThrow();
                assertEquals(TaskSubmissionResult.Status.COMPLETED,terminal.status());
                completedTaskId=terminal.taskId();
                events.flush();
                assertTrue(transcript.toString().contains("README.md"));
                assertFalse(transcript.toString().contains("private-e2e-reasoning-sentinel"));
            }
            assertEquals(2,requests.size(),"expected Tool request and continuation FINAL only");
            assertTrue(requests.stream().noneMatch(body->body.has("response_format")),
                    "default execution must not issue a mandatory Plan request");
            assertTrue(requests.stream().allMatch(body->"enabled".equals(
                    body.path("thinking").path("type").asText())));
            assertTrue(requests.stream().allMatch(body->"max".equals(
                    body.path("reasoning_effort").asText())));
            var continuation=requests.stream().filter(body->java.util.stream.StreamSupport
                    .stream(body.path("messages").spliterator(),false)
                    .anyMatch(message->message.has("tool_call_id")))
                    .findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(
                    continuation.path("messages").spliterator(),false)
                    .anyMatch(message->"private-e2e-reasoning-sentinel".equals(
                            message.path("reasoning_content").asText())));
            var assistant=java.util.stream.StreamSupport.stream(
                    continuation.path("messages").spliterator(),false)
                    .filter(message->message.has("tool_calls")).findFirst().orElseThrow();
            assertEquals("",assistant.path("content").asText());
            assertEquals("deepseek-e2e-call",
                    assistant.path("tool_calls").path(0).path("id").asText());
            try(StateStore persisted=new StateStore(workspace)){
                var task=persisted.tasks.findById(completedTaskId).orElseThrow();
                var run=persisted.codingRuns.findByTask(completedTaskId).getFirst();
                assertEquals(TaskStore.STATUS_DONE,task.status());
                assertEquals(CodingRunStore.PHASE_COMPLETED,run.phase());
                assertTrue(persisted.plans.findByRun(run.runId()).isEmpty());
                assertEquals(com.paicli.state.PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                        persisted.planAuthorities.readActive(
                                CanonicalId.parse(completedTaskId)).status());
                assertNull(persisted.userResults.find(CanonicalId.parse(completedTaskId),
                        CanonicalId.parse(run.runId())).orElseThrow().planRevisionId());
                assertTrue(persisted.events.findMessages(completedTaskId).stream()
                        .noneMatch(message->message.toString().contains(
                                "private-e2e-reasoning-sentinel")));
                assertTrue(persisted.checkpoints.loadLatest(completedTaskId).stream()
                        .flatMap(checkpoint->checkpoint.messages().stream())
                        .noneMatch(message->message.toString().contains(
                                "private-e2e-reasoning-sentinel")));
            }
            assertEquals(0,rowCount(workspace,"plan_revisions"));
            assertEquals(0,rowCount(workspace,"plan_approvals"));
            byte[] sentinel="private-e2e-reasoning-sentinel"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(var files=Files.walk(workspace)){
                assertTrue(files.filter(Files::isRegularFile).noneMatch(path->{
                    try{return indexOf(Files.readAllBytes(path),sentinel)>=0;}
                    catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
                }),"private reasoning must not enter workspace durable state or trace files");
            }
        }finally{server.stop(0);}
    }

    private static int indexOf(byte[] source,byte[] target){
        outer:for(int i=0;i<=source.length-target.length;i++){
            for(int j=0;j<target.length;j++)if(source[i+j]!=target[j])continue outer;
            return i;
        }
        return -1;
    }

    private static String response(String content){
        try{
            var root=JSON.createObjectNode();var choices=root.putArray("choices");
            var choice=choices.addObject();choice.put("finish_reason","stop");
            var message=choice.putObject("message");message.put("role","assistant");
            message.put("content",content);return JSON.writeValueAsString(root);
        }catch(Exception error){throw new AssertionError(error);}
    }

    @Test void shellInputRunsProductionRuntimeAndEmitsRealEvents() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/Sample.java"),
                "package demo; public class Sample { int value() { return 1; } }\n");
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/config.json"), """
                {
                  "provider": "ollama",
                  "approval_mode": "read-only",
                  "enable_verify": false,
                  "enable_repair": false,
                  "enable_skills": false,
                  "enable_hooks": true,
                  "max_iterations": 3,
                  "context_window_tokens": 131072
                }
                """);
        Files.writeString(workspace.resolve(".paicli/hooks.json"), """
                {"version":1,"hooks":[
                  {"id":"default-before-plan","event":"BEFORE_PLAN",
                   "command":"echo default-before-plan","failure_policy":"warn"},
                  {"id":"default-before-execute","event":"BEFORE_EXECUTE",
                   "command":"echo default-before-execute","failure_policy":"warn"}
                ]}
                """);

        StringWriter transcript = new StringWriter();
        PrintWriter output = new PrintWriter(transcript);
        PlainRuntimeEventRenderer events = new PlainRuntimeEventRenderer(output);
        Path isolatedHome = temp.resolve("isolated-home");
        Config loadedConfig = new ConfigLoader(workspace, isolatedHome).load();
        assertEquals(Integer.valueOf(131072), loadedConfig.contextWindowTokens());
        assertEquals(Integer.valueOf(131072), loadedConfig.effectiveContextWindowTokens());
        assertFalse(loadedConfig.isPlanEnabled());
        assertEquals(Config.PROVIDER_OLLAMA, loadedConfig.effectiveProvider());
        ConcurrentLinkedQueue<ModelResponse> responses = new ConcurrentLinkedQueue<>();
        responses.add(ModelResponse.toolCallResponse("inspect", new ToolCall(
                "list_files", Map.of("path", "."))));
        responses.add(ModelResponse.finalAnswer(
                "The workspace contains the inspected source file. No files were changed."));
        List<String> providerOperations = java.util.Collections.synchronizedList(new ArrayList<>());
        ProductionInteractiveTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, () -> new ConfigLoader(workspace, isolatedHome).load(),
                ignored -> (messages, tools) -> {
                    providerOperations.add(tools.isEmpty()
                            ? CodingRunStore.PHASE_PLANNING:CodingRunStore.PHASE_EXECUTING);
                    return responses.remove();
                });
        try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                workspace, executor, events)) {
            WaitingReader input = new WaitingReader(runtime);
            CaptureShellRenderer shellRenderer = new CaptureShellRenderer();
            InteractiveHistoryStore history =
                    new InteractiveHistoryStore(temp.resolve("history"));
            InteractiveShell shell = new InteractiveShell(
                    new InteractiveShellConfig("1.1.0", workspace,
                            Config.PROVIDER_OLLAMA, null, Config.MODE_READ_ONLY,
                            InteractiveSession.TerminalMode.PLAIN),
                    input, shellRenderer,
                    new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                    router(workspace, runtime), runtime, history,
                    new InteractivePromptRenderer(), Clock.systemUTC());

            assertEquals(1, shell.run().submittedTasks());
            input.assertPlanlessApprovalFlow();
            InteractiveTaskResult terminal = runtime.lastResult().orElseThrow();
            assertEquals(TaskSubmissionResult.Status.COMPLETED,
                    terminal.status(), terminal.code() + ": " + terminal.boundedMessage());
            events.flush();
            String text = transcript.toString();
            List<String> lines = text.lines().toList();
            String started = "[Task] Started: Inspect Sample and report the workspace";
            String completed = "[Task] Completed · ";
            assertEquals(1, lines.stream().filter(started::equals).count(), text);
            assertEquals(1, lines.stream().filter(line ->
                    line.startsWith(completed)).count(), text);
            int startedIndex = lines.indexOf(started);
            int completedIndex = java.util.stream.IntStream.range(0, lines.size())
                    .filter(index -> lines.get(index).startsWith(completed))
                    .findFirst().orElse(-1);
            assertTrue(startedIndex >= 0, text);
            assertTrue(completedIndex >= 0, text);
            assertTrue(startedIndex < completedIndex, text);
            assertFalse(lines.stream().anyMatch(line ->
                    line.equals("[Task] Failed")
                            || line.equals("[Task] Cancelled")
                            || line.equals("[Task] Incomplete")), text);
            assertFalse(text.contains(terminal.taskId()), text);
            assertFalse(lines.stream().anyMatch(line ->
                    line.startsWith("[Done]")
                            || line.startsWith("[Assistant]")), text);
            assertFalse(text.contains("[Plan]"));
            assertTrue(text.contains("[Explore] indexed 1 Java file(s)"));
            assertFalse(text.contains("[Tool] requested list_files"));
            try (com.paicli.state.StateStore persisted =
                    new com.paicli.state.StateStore(workspace)) {
                var run=persisted.codingRuns.findByTask(terminal.taskId()).getFirst();
                assertEquals(List.of(CodingRunStore.PHASE_EXECUTING,
                        CodingRunStore.PHASE_EXECUTING),providerOperations);
                assertEquals(0,providerOperations.stream()
                        .filter(CodingRunStore.PHASE_PLANNING::equals).count());
                assertTrue(persisted.plans.findByRun(run.runId()).isEmpty());
                assertEquals(com.paicli.state.PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                        persisted.planAuthorities.readActive(
                                CanonicalId.parse(terminal.taskId())).status());
                var hookRuns=persisted.hookRuns.findByRun(run.runId());
                assertEquals(1,hookRuns.size());
                assertEquals("default-before-execute",hookRuns.getFirst().hookId());
                var result=persisted.userResults.find(CanonicalId.parse(terminal.taskId()),
                        CanonicalId.parse(run.runId())).orElseThrow();
                assertNull(result.planRevisionId());
                assertEquals("The workspace contains the inspected source file. No files were changed.",
                        result.content());
                assertTrue(persisted.verifications.findByRun(run.runId()).isEmpty());
                assertTrue(persisted.toolCalls.findByTask(terminal.taskId()).stream()
                        .anyMatch(call -> "list_files".equals(call.toolName())));
            }
            assertEquals(0,rowCount(workspace,"plan_revisions"));
            assertEquals(0,rowCount(workspace,"plan_approvals"));
            assertTrue(responses.isEmpty(),"planless Tool and FINAL responses must be consumed");
            assertTrue(text.contains("[Result]"),text);
            assertTrue(text.contains("The workspace contains the inspected source file"),text);
            assertFalse(text.contains("INTERACTIVE_PREVIEW_UNAVAILABLE"));
            assertFalse(text.contains("D:\\"));
            assertTrue(Files.exists(workspace.resolve(".paicli/state/paicli.db")));
        }
    }

    @Test void qwenMetadataLoopbackPreservesInternalOllamaAdapterPlanningRegression()
            throws Exception {
        Path workspace = temp.resolve("qwen-metadata-workspace");
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("README.md"),"UAT workspace guide\n");
        Files.writeString(workspace.resolve("notes.txt"),"read-only test notes\n");
        Files.writeString(workspace.resolve("docs/guide.md"),"test guide\n");
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger chatCalls = new AtomicInteger();
        List<String> loopbackExchanges = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/show", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes());
            assertEquals("qwen3:14b", JSON.readTree(request).path("model").asText());
            metadataCalls.incrementAndGet();
            writeJson(exchange, """
                    {"details":{"family":"qwen3"},"model_info":{
                      "general.architecture":"qwen3",
                      "qwen3.context_length":40960
                    }}
                    """);
        });
        server.createContext("/api/chat", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes());
            var requestJson=JSON.readTree(request);
            assertEquals("qwen3:14b", requestJson.path("model").asText());
            int call = chatCalls.incrementAndGet();
            String content = switch (call) {
                case 1 -> readTwoStepPlan().content();
                case 4 -> "Current workspace files:\n- README.md — UAT workspace guide.\n"
                        +"- notes.txt — read-only test notes.\n"
                        +"- docs/guide.md — test guide.\n"
                        +"No files were created, modified, or deleted.";
                default -> "";
            };
            var response = JSON.createObjectNode();
            response.put("prompt_eval_count", 100);
            response.put("eval_count", 20);
            response.put("done", true);
            response.put("done_reason", "stop");
            var message = response.putObject("message");
            message.put("role", "assistant");
            message.put("content", call == 4
                    ? JSON.writeValueAsString(Map.of("userAnswer", content)) : content);
            if (call == 2 || call == 3) {
                var function = message.putArray("tool_calls").addObject()
                        .put("id", call==2?"call-list-root":"call-list-docs")
                        .putObject("function");
                function.put("name", "list_files");
                function.putObject("arguments").put("path", call==2?".":"docs");
            }
            if(call==3)assertTrue(request.contains("call-list-root"),
                    "root Tool continuation identity missing");
            if(call==4)assertTrue(request.contains("call-list-docs"),
                    "docs Tool continuation identity missing");
            loopbackExchanges.add("call="+call+",tool="+(call==2||call==3)
                    +",content="+!content.isBlank());
            writeJson(exchange, JSON.writeValueAsString(response));
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Files.writeString(workspace.resolve(".paicli/config.json"), """
                    {"provider":"ollama","model":"qwen3:14b",
                     "base_url":"http://127.0.0.1:%d",
                     "enable_plan":true,
                     "approval_mode":"read-only","enable_verify":false,
                     "enable_repair":false,"enable_skills":false,
                     "enable_hooks":false,"max_iterations":3}
                    """.formatted(port));
            StringWriter transcript = new StringWriter();
            PlainRuntimeEventRenderer events = new PlainRuntimeEventRenderer(
                    new PrintWriter(transcript));
            ProductionInteractiveTaskExecutor executor =
                    new ProductionInteractiveTaskExecutor(workspace);
            try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                    workspace, executor, events)) {
                InteractiveSession session = new InteractiveSession(
                        "session-qwen-metadata", workspace, "ollama", "qwen3:14b",
                        "read-only", InteractiveSession.TerminalMode.PLAIN, Instant.now());
                session.start();
                runtime.openSession(session);
                TaskSubmissionResult submitted = runtime.submit(
                        new com.paicli.interactive.task.InteractiveTaskRequest(
                                session.snapshot().sessionId(), workspace,
                                "列出当前工作目录及其子目录中的文件，并简要说明每个文件的用途。不要创建、修改或删除任何文件。",
                                List.of()));
                assertEquals(TaskSubmissionResult.Status.PREPARING, submitted.status());
                var foreground = runtime.activeForegroundTask().orElseThrow();
                foreground.admission().get(5, java.util.concurrent.TimeUnit.SECONDS);
                for (int attempt = 0; attempt < 500
                        && runtime.pendingApproval().isEmpty(); attempt++) {
                    Thread.sleep(10);
                }
                InteractiveApprovalRequest approval = runtime.pendingApproval().orElseThrow();
                assertEquals(InteractiveApprovalRequest.Kind.PLAN, approval.kind());
                assertTrue(runtime.respondApproval(approval.requestId(), "y").resolved());
                TaskSubmissionResult terminal = foreground.terminal().get(10,
                        java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(TaskSubmissionResult.Status.COMPLETED, terminal.status(),
                        ()->"terminalStatus="+terminal.status()+",code="+terminal.code()
                                +",message="+terminal.message()+",metadataCalls="
                                +metadataCalls.get()+",chatCalls="+chatCalls.get()
                                +",loopback="+loopbackExchanges+",transcript="+transcript);
                assertTrue(runtime.awaitIdle(Duration.ofSeconds(2)));
                assertTrue(runtime.pendingApproval().isEmpty());
                assertNull(session.snapshot().currentTaskId());
                assertEquals("IDLE", runtime.view().snapshot().phase());
                try (StateStore persisted = new StateStore(workspace)) {
                    CanonicalId taskId = CanonicalId.parse(foreground.taskId());
                    assertEquals("COMPLETED",
                            persisted.r1aDurable.findTask(taskId).orElseThrow().status());
                    assertEquals("COMPLETED", persisted.r1aDurable.findRunsByTask(taskId)
                            .getFirst().terminalStatus());
                    var active = persisted.planAuthorities.readActive(taskId);
                    assertEquals(com.paicli.state.PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                            active.status());
                    var approved = persisted.planAuthorities.readLatestApprovedRevision(taskId);
                    assertEquals(com.paicli.state.PlanAuthorityStore.Status.SUCCESS,
                            approved.status());
                    assertEquals("R1C_A_STRUCTURED_PLAN:ADVISORY_WIRE_V1",
                            approved.revision().provenance());
                    assertTrue(persisted.planExecutions.readStates(taskId,
                            approved.revision().planRevisionId()).isEmpty());
                    String integrityFindings = persisted.schemaIntegrity.check().findings().toString();
                    assertEquals(com.paicli.interactive.view.OperationalStatusView.Status.READY,
                            persisted.viewModels.read(session.snapshot(),
                                    runtime.view().snapshot()).operationalStatus().primary(),
                            integrityFindings);
                }
                List<String> report=new com.paicli.interactive.query.StateStoreInteractiveQueryFacade(
                        workspace,runtime.view()).report(session.snapshot(),null);
                assertTrue(report.contains("──────────────── PaiCLI Report ─────────────────────────"),report.toString());
                assertTrue(report.stream().anyMatch(line->line.contains("README.md")),report.toString());
                assertTrue(report.contains("  [Result]"), report.toString());
                assertTrue(report.contains("  Current workspace files:"), report.toString());
                assertTrue(report.stream().anyMatch(line->line.contains("docs/guide.md")),
                        report.toString());
            }
            events.flush();
            assertEquals(1, metadataCalls.get());
            assertEquals(4, chatCalls.get());
            assertTrue(transcript.toString().contains("[Plan]"), transcript.toString());
            assertTrue(transcript.toString().contains("[Task] Completed"),
                    transcript.toString());
            assertTrue(transcript.toString().contains("• Running list_files"));
            assertTrue(transcript.toString().contains("README.md"));
            assertTrue(transcript.toString().contains("notes.txt"));
            assertTrue(transcript.toString().contains("guide.md"));
        } finally {
            server.stop(0);
        }
    }

    @Test void specificFileQuestionUsesDirectAdvisoryToolChainBeforeCompleting()
            throws Exception {
        Path workspace=temp.resolve("specific-file-evidence-workspace");
        Files.createDirectories(workspace.resolve(".paicli"));
        String readme="This README documents the bounded evidence recovery fixture.\n";
        Files.writeString(workspace.resolve("README.md"),readme);
        Files.writeString(workspace.resolve(".paicli/config.json"),"""
                {"provider":"ollama","approval_mode":"read-only",
                 "enable_plan":true,
                 "enable_verify":false,"enable_repair":false,"enable_skills":false,
                 "enable_hooks":false,"max_iterations":4,"context_window_tokens":131072}
                """);
        ConcurrentLinkedQueue<ModelResponse> responses=new ConcurrentLinkedQueue<>();
        responses.add(readFilePlan("README.md"));
        responses.add(ModelResponse.toolCallResponse("Read the requested file",
                new ToolCall("read_file",Map.of("path","README.md"),"call-readme-1")));
        responses.add(ModelResponse.finalAnswer(
                "README.md documents the bounded evidence recovery fixture. No files were modified."));
        StringWriter transcript=new StringWriter();
        PlainRuntimeEventRenderer events=new PlainRuntimeEventRenderer(new PrintWriter(transcript));
        ProductionInteractiveTaskExecutor executor=new ProductionInteractiveTaskExecutor(
                workspace,ignored->scripted(responses));

        try(InteractiveRuntimeAdapter runtime=InteractiveRuntimeAdapter.open(
                workspace,executor,events)){
            InteractiveSession session=new InteractiveSession("session-specific-file",workspace,
                    "ollama",null,"read-only",InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();runtime.openSession(session);
            TaskSubmissionResult submitted=runtime.submit(
                    new com.paicli.interactive.task.InteractiveTaskRequest(
                            session.snapshot().sessionId(),workspace,
                            "说明 README.md 的用途，不要修改任何文件。",List.of()));
            assertEquals(TaskSubmissionResult.Status.PREPARING,submitted.status());
            var foreground=runtime.activeForegroundTask().orElseThrow();
            var admitted=foreground.admission().get(5,java.util.concurrent.TimeUnit.SECONDS);
            for(int attempt=0;attempt<500&&runtime.pendingApproval().isEmpty();attempt++)
                Thread.sleep(10);
            InteractiveApprovalRequest approval=runtime.pendingApproval().orElseThrow();
            assertTrue(runtime.respondApproval(approval.requestId(),"y").resolved());
            TaskSubmissionResult terminal=foreground.terminal().get(10,
                    java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(TaskSubmissionResult.Status.COMPLETED,terminal.status(),
                    terminal.code()+": "+terminal.message());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(2)));
            events.flush();
            try(StateStore state=new StateStore(workspace)){
                var calls=state.toolCalls.findByTask(admitted.taskId());
                assertEquals(1,calls.size());
                assertEquals("read_file",calls.getFirst().toolName());
                assertTrue(calls.getFirst().success());
                assertTrue(calls.getFirst().output().contains(readme.strip()));
            }
            assertTrue(transcript.toString().contains("Running read_file"),transcript.toString());
            assertTrue(responses.isEmpty(),"all direct advisory responses must be consumed");
            assertEquals("This README documents the bounded evidence recovery fixture.\n",
                    Files.readString(workspace.resolve("README.md")));
        }
    }

    @Test void repeatedSafeReadDoesNotUseStrictPlanCardinalityInAdvisoryMode()throws Exception{
        Path workspace=temp.resolve("no-progress-workspace");
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve("README.md"),"no-progress fixture\n");
        Files.writeString(workspace.resolve(".paicli/config.json"),"""
                {"provider":"ollama","approval_mode":"read-only","enable_verify":false,
                 "enable_plan":true,
                 "enable_repair":false,"enable_skills":false,"enable_hooks":false,
                 "max_iterations":20,"context_window_tokens":131072}
                """);
        ConcurrentLinkedQueue<ModelResponse> responses=new ConcurrentLinkedQueue<>();
        responses.add(readPlan());
        responses.add(ModelResponse.toolCallResponse("inspect",new ToolCall(
                "list_files",Map.of("path","."),"call-loop-1")));
        responses.add(ModelResponse.toolCallResponse("inspect again",new ToolCall(
                "list_files",Map.of("path","."),"call-loop-2")));
        responses.add(ModelResponse.finalAnswer("workspace inspected after two safe reads"));
        StringWriter transcript=new StringWriter();
        PlainRuntimeEventRenderer events=new PlainRuntimeEventRenderer(new PrintWriter(transcript));
        ProductionInteractiveTaskExecutor executor=new ProductionInteractiveTaskExecutor(
                workspace,ignored->scripted(responses));
        try(InteractiveRuntimeAdapter runtime=InteractiveRuntimeAdapter.open(
                workspace,executor,events)){
            InteractiveSession session=new InteractiveSession("session-no-progress",workspace,
                    "ollama",null,"read-only",InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();runtime.openSession(session);
            runtime.submit(new com.paicli.interactive.task.InteractiveTaskRequest(
                    session.snapshot().sessionId(),workspace,"List workspace files.",List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();
            var admitted=foreground.admission().get(5,java.util.concurrent.TimeUnit.SECONDS);
            for(int attempt=0;attempt<500&&runtime.pendingApproval().isEmpty();attempt++)
                Thread.sleep(10);
            var approval=runtime.pendingApproval().orElseThrow();
            assertTrue(runtime.respondApproval(approval.requestId(),"y").resolved());
            TaskSubmissionResult terminal=foreground.terminal().get(10,
                    java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(TaskSubmissionResult.Status.COMPLETED,terminal.status(),
                    terminal.message());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(2)));
            events.flush();
            try(StateStore state=new StateStore(workspace)){
                assertEquals(2,state.toolCalls.findByTask(admitted.taskId()).size());
                assertEquals("COMPLETED",state.r1aDurable.findTask(
                        CanonicalId.parse(admitted.taskId())).orElseThrow().status());
                assertEquals("COMPLETED",state.r1aDurable.findRunsByTask(
                        CanonicalId.parse(admitted.taskId())).getFirst().terminalStatus());
            }
            assertFalse(transcript.toString().contains("segment iteration limit"),
                    transcript.toString());
            assertTrue(responses.isEmpty(),"the advisory loop must continue to the final response");
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange,
            String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Test void onRequestApprovalShowsPreWriteDiffBeforeSnapshotAndMutation() throws Exception {
        Path workspace = temp.resolve("approval-workspace");
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        String before = "package demo; public class Sample { int value() { return 1; } }\n";
        String after = "package demo; public class Sample { int value() { return 2; } }\n";
        Files.writeString(source, before);
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/config.json"), """
                {
                  "provider": "ollama",
                  "approval_mode": "on-request",
                  "enable_plan": true,
                  "enable_verify": false,
                  "enable_repair": false,
                  "enable_skills": false,
                  "enable_hooks": false,
                  "max_iterations": 3,
                  "context_window_tokens": 131072
                }
                """);

        Config loadedConfig = new ConfigLoader(workspace).load();
        assertEquals(Integer.valueOf(131072), loadedConfig.contextWindowTokens());
        assertEquals(Integer.valueOf(131072), loadedConfig.effectiveContextWindowTokens());
        assertTrue(loadedConfig.isPlanEnabled());
        assertEquals(Config.PROVIDER_OLLAMA, loadedConfig.effectiveProvider());

        ConcurrentLinkedQueue<ModelResponse> responses = new ConcurrentLinkedQueue<>();
        responses.add(writePlan());
        responses.add(ModelResponse.toolCallResponse("read", new ToolCall(
                "read_file", Map.of("path", "src/main/java/demo/Sample.java"))));
        responses.add(ModelResponse.toolCallResponse("edit", new ToolCall(
                "write_file", Map.of("path", "src/main/java/demo/Sample.java",
                        "content", after, "overwrite", true))));
        responses.add(ModelResponse.finalAnswer(
                "Updated src/main/java/demo/Sample.java as approved; verification was not run."));
        List<AgentEvent> emitted = java.util.Collections.synchronizedList(new ArrayList<>());
        ProductionInteractiveTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, ignored -> (messages, tools) -> {
                    ModelResponse response = responses.poll();
                    if (response == null) throw new IllegalStateException("provider script exhausted");
                    return response;
                });

        String approvedTaskId;
        String approvedRunId;
        try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                workspace, executor, emitted::add)) {
            ApprovalReader input = new ApprovalReader(runtime, workspace, source, before, "y");
            InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("approval-history"));
            InteractiveShell shell = new InteractiveShell(
                    new InteractiveShellConfig("1.1.0", workspace,
                            Config.PROVIDER_OLLAMA, null, Config.MODE_ON_REQUEST,
                            InteractiveSession.TerminalMode.PLAIN),
                    input, new CaptureShellRenderer(),
                    new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                    router(workspace, runtime), runtime, history,
                    new InteractivePromptRenderer(), Clock.systemUTC());

            assertEquals(1, shell.run().submittedTasks());
            InteractiveTaskResult terminal = runtime.lastResult().orElseThrow();
            approvedTaskId = terminal.taskId();
            approvedRunId = terminal.runId();
            assertNotNull(approvedRunId);
            assertEquals(after, Files.readString(source));
            assertEquals(TaskSubmissionResult.Status.COMPLETED_UNVERIFIED, terminal.status());
            input.assertApprovalFlow(true);
            assertTrue(responses.isEmpty(), "scripted provider responses must be exhausted");
            assertFalse(history.load().contains("y"));
        }

        List<AgentEventType> types = emitted.stream()
                .map(event -> event.metadata().eventType()).toList();
        int proposed = types.indexOf(AgentEventType.DIFF_PROPOSED);
        int requested = types.lastIndexOf(AgentEventType.APPROVAL_REQUESTED);
        int resolved = types.lastIndexOf(AgentEventType.APPROVAL_RESOLVED);
        int snapshot = types.indexOf(AgentEventType.SNAPSHOT_CREATED);
        int written = types.indexOf(AgentEventType.FILE_WRITTEN);
        assertEquals(1, types.stream().filter(type -> type == AgentEventType.TASK_STARTED).count());
        assertEquals(1, types.stream().filter(type ->
                type == AgentEventType.SNAPSHOT_CREATED).count());
        assertEquals(1, types.stream().filter(type ->
                type == AgentEventType.FILE_WRITTEN).count());
        assertTrue(proposed >= 0 && proposed < requested);
        assertTrue(requested < resolved && resolved < snapshot && snapshot < written, types.toString());
        assertEquals(List.of(AgentEventType.TASK_COMPLETED),
                types.stream().filter(ProductionPathIntegrationIT::isTerminalEvent).toList());
        assertTrue(emitted.stream().filter(event -> event.metadata().taskId() != null)
                .allMatch(event -> approvedTaskId.equals(event.metadata().taskId())));
        assertTrue(emitted.stream().filter(event -> event.metadata().runId() != null)
                .allMatch(event -> approvedRunId.equals(event.metadata().runId())));
        try (StateStore state = new StateStore(workspace)) {
            var task = state.tasks.findById(approvedTaskId).orElseThrow();
            assertEquals(TaskStore.STATUS_COMPLETED_UNVERIFIED, task.status());
            var run = state.codingRuns.findById(approvedRunId).orElseThrow();
            assertEquals(approvedTaskId, run.taskId());
            assertEquals(CodingRunStore.PHASE_COMPLETED, run.phase());

            CanonicalId canonicalTaskId=CanonicalId.parse(approvedTaskId);
            assertEquals(com.paicli.state.PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                    state.planAuthorities.readActive(canonicalTaskId).status());
            var advisory=state.planAuthorities.readLatestApprovedRevision(canonicalTaskId);
            assertEquals(com.paicli.state.PlanAuthorityStore.Status.SUCCESS,advisory.status());
            assertTrue(state.planExecutions.readStates(canonicalTaskId,
                    advisory.revision().planRevisionId()).isEmpty());
            assertTrue(state.planCompletions.readRecovery(canonicalTaskId,
                    advisory.revision().planRevisionId()).evidence().isEmpty());

            var snapshots = state.snapshots.findByTask(approvedTaskId);
            assertEquals(1, snapshots.size());
            assertEquals(approvedTaskId, snapshots.getFirst().taskId());
            assertEquals(com.paicli.state.FileSnapshotStore.STATUS_APPLIED,
                    snapshots.getFirst().status());
            var proposals = state.interactiveProposals.findByTask(approvedTaskId, 10);
            assertEquals(1, proposals.size());
            assertEquals(approvedTaskId, proposals.getFirst().taskId());
            assertEquals(approvedRunId, proposals.getFirst().runId());
            assertEquals(InteractiveProposalStore.APPLIED, proposals.getFirst().status());
            assertEquals(snapshots.getFirst().snapshotId(), proposals.getFirst().snapshotId());

            var calls = state.toolCalls.findByTask(approvedTaskId);
            assertEquals(List.of("read_file", "write_file"),
                    calls.stream().map(call -> call.toolName()).toList());
            assertTrue(calls.stream().allMatch(call -> call.success()
                    && approvedTaskId.equals(call.taskId())));
            assertTrue(calls.getFirst().id() < calls.getLast().id());
            assertEquals("ALLOW",
                    state.approvals.findByTask(approvedTaskId).getLast().decision());
        }
    }
    @Test void rejectedProposalCreatesNoSnapshotAndDoesNotMutateFile() throws Exception {
        Path workspace = temp.resolve("reject-workspace");
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        String before = "package demo; class Sample { int value() { return 1; } }\n";
        Files.writeString(source, before);
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/config.json"), """
                {"provider":"ollama","approval_mode":"on-request",
                 "enable_plan":true,
                 "enable_verify":true,"enable_repair":true,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":3,
                 "context_window_tokens":131072}
                """);
        Config loadedConfig = new ConfigLoader(workspace).load();
        assertEquals(Integer.valueOf(131072), loadedConfig.contextWindowTokens());
        assertEquals(Integer.valueOf(131072), loadedConfig.effectiveContextWindowTokens());
        assertTrue(loadedConfig.isPlanEnabled());
        assertEquals(Config.PROVIDER_OLLAMA, loadedConfig.effectiveProvider());
        ConcurrentLinkedQueue<ModelResponse> responses = new ConcurrentLinkedQueue<>();
        responses.add(writePlan());
        responses.add(ModelResponse.toolCallResponse("read", new ToolCall("read_file",
                Map.of("path", "src/main/java/demo/Sample.java"))));
        responses.add(ModelResponse.toolCallResponse("edit", new ToolCall("write_file",
                Map.of("path", "src/main/java/demo/Sample.java",
                        "content", before.replace("return 1", "return 2"),
                        "overwrite", true))));
        responses.add(ModelResponse.finalAnswer("write proposal was rejected; no file changed"));
        List<AgentEvent> emitted = java.util.Collections.synchronizedList(new ArrayList<>());
        ProductionInteractiveTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, ignored -> (messages, tools) -> responses.remove());
        InteractiveHistoryStore rejectHistory =
                new InteractiveHistoryStore(temp.resolve("reject-history"));
        String rejectedTaskId;
        String rejectedRunId;
        try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                workspace, executor, emitted::add)) {
            ApprovalReader input = new ApprovalReader(runtime, workspace, source, before, "n");
            InteractiveShell shell = new InteractiveShell(
                    new InteractiveShellConfig("1.1.0", workspace, Config.PROVIDER_OLLAMA,
                            null, Config.MODE_ON_REQUEST, InteractiveSession.TerminalMode.PLAIN),
                    input,
                    new CaptureShellRenderer(),
                    new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                    router(workspace, runtime), runtime,
                    rejectHistory,
                    new InteractivePromptRenderer(), Clock.systemUTC());
            shell.run();
            InteractiveTaskResult terminal = runtime.lastResult().orElseThrow();
            rejectedTaskId = terminal.taskId();
            rejectedRunId = terminal.runId();
            assertNotNull(rejectedRunId);
            assertEquals(TaskSubmissionResult.Status.INCOMPLETE,
                    terminal.status(),terminal.code()+": "+terminal.boundedMessage());
            input.assertApprovalFlow(true);
            assertTrue(responses.isEmpty(), "scripted provider responses must be exhausted");
        }
        assertFalse(rejectHistory.load().contains("n"));
        List<AgentEventType> rejectedTypes = emitted.stream()
                .map(event -> event.metadata().eventType()).toList();
        assertEquals(1, rejectedTypes.stream()
                .filter(type -> type == AgentEventType.TASK_STARTED).count());
        assertEquals(List.of(AgentEventType.TASK_FAILED),
                rejectedTypes.stream().filter(ProductionPathIntegrationIT::isTerminalEvent).toList());
        assertTrue(emitted.stream().filter(event -> event.metadata().taskId() != null)
                .allMatch(event -> rejectedTaskId.equals(event.metadata().taskId())));
        assertTrue(emitted.stream().filter(event -> event.metadata().runId() != null)
                .allMatch(event -> rejectedRunId.equals(event.metadata().runId())));
        assertEquals(before, Files.readString(source));
        assertFalse(emitted.stream().anyMatch(event -> event.metadata().eventType()
                == AgentEventType.SNAPSHOT_CREATED));
        assertFalse(emitted.stream().anyMatch(event -> event.metadata().eventType()
                == AgentEventType.FILE_WRITTEN));
        assertFalse(rejectedTypes.stream().anyMatch(type ->
                type == AgentEventType.VERIFICATION_STARTED
                        || type == AgentEventType.VERIFICATION_PASSED
                        || type == AgentEventType.VERIFICATION_FAILED
                        || type == AgentEventType.REPAIR_STARTED));
        try (StateStore state = new StateStore(workspace)) {
            var task = state.tasks.findById(rejectedTaskId).orElseThrow();
            assertEquals(TaskStore.STATUS_REJECTED, task.status());
            var run = state.codingRuns.findById(rejectedRunId).orElseThrow();
            assertEquals(rejectedTaskId, run.taskId());
            assertEquals(CodingRunStore.PHASE_COMPLETED,run.phase());
            assertTrue(state.codingRuns.findActiveByTask(rejectedTaskId).isEmpty());
            CanonicalId taskId=CanonicalId.parse(rejectedTaskId);
            assertEquals(com.paicli.state.PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                    state.planAuthorities.readActive(taskId).status());
            var advisory=state.planAuthorities.readLatestApprovedRevision(taskId);
            assertEquals(com.paicli.state.PlanAuthorityStore.Status.SUCCESS,advisory.status());
            assertTrue(state.planExecutions.readStates(taskId,
                    advisory.revision().planRevisionId()).isEmpty());
            assertTrue(state.planCompletions.readRecovery(taskId,
                    advisory.revision().planRevisionId()).evidence().isEmpty());

            assertTrue(state.snapshots.findByTask(rejectedTaskId).isEmpty());
            var proposals = state.interactiveProposals.findByTask(rejectedTaskId, 10);
            assertEquals(1, proposals.size());
            assertEquals(rejectedTaskId, proposals.getFirst().taskId());
            assertEquals(rejectedRunId, proposals.getFirst().runId());
            assertEquals(InteractiveProposalStore.REJECTED, proposals.getFirst().status());
            assertNull(proposals.getFirst().snapshotId());
            var calls = state.toolCalls.findByTask(rejectedTaskId);
            assertEquals(1, calls.stream().filter(call ->
                    "read_file".equals(call.toolName()) && call.success()).count());
            assertFalse(calls.stream().anyMatch(call ->
                    "write_file".equals(call.toolName()) && call.success()));
            assertTrue(calls.stream().allMatch(call ->
                    rejectedTaskId.equals(call.taskId())));
            var denial = state.approvals.findByTask(rejectedTaskId).stream()
                    .filter(approval -> "write_file".equals(approval.toolName())
                            && "DENY".equals(approval.decision()))
                    .findFirst().orElseThrow();
            assertTrue(denial.reason().contains("rejected current action"));
            assertTrue(denial.reason().contains("task may continue"));
            assertTrue(state.verifications.findLatestByRun(run.runId()).isEmpty());
            assertTrue(state.repairAttempts.findByRun(run.runId()).isEmpty());
        }
        var query = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView());
        var direct = query.taskViews(rejectedTaskId, 1);
        var active = query.taskViews("active", 10);
        var recoverable = query.taskViews("recoverable", 10);
        List<String> activeIds = active.stream().map(task -> task.taskId()).toList();
        List<String> recoverableIds = recoverable.stream().map(task -> task.taskId()).toList();
        String baseDiagnostic = "expectedTaskId=" + rejectedTaskId
                + ",direct=" + direct
                + ",activeIds=" + activeIds
                + ",recoverableIds=" + recoverableIds;
        var directTask = direct.stream()
                .filter(task -> rejectedTaskId.equals(task.taskId()))
                .findFirst().orElseThrow(() -> new AssertionError(baseDiagnostic));
        String queryDiagnostic = baseDiagnostic
                + ",directView={status=" + directTask.status()
                + ",phase=" + directTask.phase()
                + ",resumable=" + directTask.resumable() + "}";
        assertEquals(TaskStore.STATUS_REJECTED, directTask.status(), () -> queryDiagnostic);
        assertEquals(CodingRunStore.PHASE_COMPLETED, directTask.phase(),
                () -> queryDiagnostic);
        assertFalse(directTask.resumable(), () -> queryDiagnostic);
        assertFalse(activeIds.contains(rejectedTaskId), () -> queryDiagnostic);
        assertFalse(recoverableIds.contains(rejectedTaskId), () -> queryDiagnostic);
    }
    @Test void approvalCancelPersistsCancellationWithoutFailureOrWriteFacts() throws Exception {
        Path workspace = temp.resolve("cancel-workspace");
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        String before = "package demo; class Sample { int value() { return 1; } }\n";
        Files.writeString(source, before);
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/config.json"), """
                {"provider":"ollama","approval_mode":"on-request",
                 "enable_plan":true,
                 "enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":3,
                 "context_window_tokens":131072}
                """);
        Config loadedConfig = new ConfigLoader(workspace).load();
        assertEquals(Integer.valueOf(131072), loadedConfig.contextWindowTokens());
        assertEquals(Integer.valueOf(131072), loadedConfig.effectiveContextWindowTokens());
        assertTrue(loadedConfig.isPlanEnabled());
        assertEquals(Config.PROVIDER_OLLAMA, loadedConfig.effectiveProvider());
        ConcurrentLinkedQueue<ModelResponse> responses = new ConcurrentLinkedQueue<>();
        responses.add(writePlan());
        responses.add(ModelResponse.toolCallResponse("read", new ToolCall("read_file",
                Map.of("path", "src/main/java/demo/Sample.java"))));
        responses.add(ModelResponse.toolCallResponse("edit", new ToolCall("write_file",
                Map.of("path", "src/main/java/demo/Sample.java",
                        "content", before.replace("return 1", "return 2"),
                        "overwrite", true))));
        List<AgentEvent> emitted = java.util.Collections.synchronizedList(new ArrayList<>());
        ProductionInteractiveTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, ignored -> (messages, tools) -> responses.remove());
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("cancel-history"));
        InteractiveRuntimeView view;
        try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                workspace, executor, emitted::add)) {
            view = runtime.view();
            ApprovalReader input = new ApprovalReader(runtime, workspace, source, before, "c");
            InteractiveShell shell = new InteractiveShell(
                    new InteractiveShellConfig("1.1.0", workspace, Config.PROVIDER_OLLAMA,
                            null, Config.MODE_ON_REQUEST, InteractiveSession.TerminalMode.PLAIN),
                    input,
                    new CaptureShellRenderer(),
                    new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                    router(workspace, runtime), runtime, history,
                    new InteractivePromptRenderer(), Clock.systemUTC());
            shell.run();
            assertEquals(TaskSubmissionResult.Status.CANCELLED,
                    runtime.lastResult().orElseThrow().status());
            input.assertApprovalFlow(true);
        }
        assertEquals(before, Files.readString(source));
        assertFalse(history.load().contains("c"));
        assertTrue(emitted.stream().anyMatch(event -> event.metadata().eventType()
                == AgentEventType.TASK_CANCELLED));
        List<AgentEventType> eventTypes = emitted.stream()
                .map(event -> event.metadata().eventType()).toList();
        int cancellationRequested = eventTypes.indexOf(AgentEventType.CANCELLATION_REQUESTED);
        assertTrue(cancellationRequested >= 0);
        assertTrue(cancellationRequested < eventTypes.indexOf(AgentEventType.TASK_CANCELLED));
        assertEquals(1, emitted.stream().filter(event -> event.metadata().eventType()
                == AgentEventType.TASK_STARTED).count());
        assertFalse(emitted.stream().anyMatch(event -> event.metadata().eventType()
                == AgentEventType.TASK_FAILED));
        assertTrue(view.snapshot().lastDiffs().stream()
                .anyMatch(diff -> "CANCELLED".equals(diff.status())));
        assertEquals("CANCELLED", view.snapshot().lastStatus());
        assertFalse(view.snapshot().lastDiffs().stream()
                .anyMatch(diff -> "SUPERSEDED".equals(diff.status())));
        try (StateStore state = new StateStore(workspace)) {
            var task = state.tasks.findAll(10).getFirst();
            assertEquals(TaskStore.STATUS_CANCELLED, task.status());
            var run = state.codingRuns.findRecentByTask(task.taskId(), 1).getFirst();
            assertEquals(com.paicli.state.CodingRunStore.PHASE_CANCELLED, run.phase());
            assertTrue(state.snapshots.findByTask(task.taskId()).isEmpty());
            assertEquals(0, state.toolCalls.findByTask(task.taskId()).stream()
                    .filter(call -> "write_file".equals(call.toolName()) && call.success()).count());
            assertTrue(state.verifications.findLatestByRun(run.runId()).isEmpty());
            state.plans.findByRun(run.runId()).ifPresent(plan ->
                    assertEquals(com.paicli.state.TaskPlanStore.STATUS_COMPLETED, plan.status()));
        }
    }
    private SlashCommandRouter router(Path workspace, InteractiveRuntimeAdapter runtime) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        return new SlashCommandRouter("1.1.0", registry,
                new SlashCommandParser(registry), () -> List.of("Overall: PASS"),
                new StateStoreInteractiveQueryFacade(workspace, runtime.view()));
    }

    private static boolean isTerminalEvent(AgentEventType type) {
        return type == AgentEventType.TASK_COMPLETED
                || type == AgentEventType.TASK_FAILED
                || type == AgentEventType.TASK_CANCELLED
                || type == AgentEventType.TASK_INCONCLUSIVE;
    }

    private static ModelResponse readPlan() {
        return ModelResponse.finalAnswer(
                "{\"acceptanceCriteria\":[\"workspace inspected\"],\"assumptions\":[],"
                        + "\"risks\":[],\"verificationStrategy\":\"inspect tool evidence\","
                        + "\"steps\":[{\"title\":\"Inspect\",\"definition\":"
                        + "\"Inspect the workspace without mutation\",\"dependsOn\":[],"
                        + "\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        + "\"requiredEvidenceKinds\":[\"TOOL_CALL\"],"
                        + "\"effectClass\":\"READ\",\"toolRequirements\":[{"
                        + "\"requirementId\":\"list-workspace\",\"requirementOrder\":0,"
                        + "\"toolName\":\"list_files\",\"effectClass\":\"READ\","
                        + "\"authoritySelector\":{\"kind\":\"EXACT_PATH\",\"path\":\".\","
                        + "\"includeInternal\":false},\"minSuccesses\":1,\"maxOccurrences\":1,"
                        + "\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]}]}");
    }

    private static long rowCount(Path workspace,String table)throws Exception{
        try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=database.getConnection().createStatement();
                var rows=statement.executeQuery("SELECT count(*) FROM "+table)){
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static ModelResponse readFilePlan(String path) {
        return ModelResponse.finalAnswer(
                "{\"acceptanceCriteria\":[\"requested file inspected\"],\"assumptions\":[],"
                        + "\"risks\":[],\"verificationStrategy\":\"inspect tool evidence\","
                        + "\"steps\":[{\"title\":\"Inspect file\",\"definition\":"
                        + "\"Read the requested file without mutation\",\"dependsOn\":[],"
                        + "\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        + "\"requiredEvidenceKinds\":[\"TOOL_CALL\"],\"effectClass\":\"READ\","
                        + "\"toolRequirements\":[{\"requirementId\":\"read-requested-file\","
                        + "\"requirementOrder\":0,\"toolName\":\"read_file\",\"effectClass\":\"READ\","
                        + "\"authoritySelector\":{\"kind\":\"EXACT_PATH\",\"path\":\"" + path + "\"},"
                        + "\"minSuccesses\":1,\"maxOccurrences\":1,"
                        + "\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]}]}");
    }

    private static ModelResponse readTwoStepPlan(){
        return ModelResponse.finalAnswer(
                "{\"acceptanceCriteria\":[\"workspace files described\"],\"assumptions\":[],"
                        +"\"risks\":[],\"verificationStrategy\":\"inspect tool evidence\","
                        +"\"steps\":[{\"title\":\"Inspect root\",\"definition\":"
                        +"\"List the workspace root\",\"dependsOn\":[],"
                        +"\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        +"\"requiredEvidenceKinds\":[\"TOOL_CALL\"],\"effectClass\":\"READ\","
                        +"\"toolRequirements\":[{\"requirementId\":\"list-root\",\"requirementOrder\":0,"
                        +"\"toolName\":\"list_files\",\"effectClass\":\"READ\","
                        +"\"authoritySelector\":{\"kind\":\"EXACT_PATH\",\"path\":\".\","
                        +"\"includeInternal\":false},\"minSuccesses\":1,\"maxOccurrences\":1,"
                        +"\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]},"
                        +"{\"title\":\"Inspect docs\",\"definition\":\"List the docs directory\","
                        +"\"dependsOn\":[0],\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        +"\"requiredEvidenceKinds\":[\"TOOL_CALL\"],\"effectClass\":\"READ\","
                        +"\"toolRequirements\":[{\"requirementId\":\"list-docs\",\"requirementOrder\":0,"
                        +"\"toolName\":\"list_files\",\"effectClass\":\"READ\","
                        +"\"authoritySelector\":{\"kind\":\"EXACT_PATH\",\"path\":\"docs\","
                        +"\"includeInternal\":false},\"minSuccesses\":1,\"maxOccurrences\":1,"
                        +"\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]}]}"
        );
    }

    private static ModelResponse writePlan() {
        return ModelResponse.finalAnswer(
                "{\"acceptanceCriteria\":[\"scoped source update decided\"],"
                        + "\"assumptions\":[],\"risks\":[\"source mutation requires approval\"],"
                        + "\"verificationStrategy\":\"inspect read and write tool evidence\","
                        + "\"steps\":[{\"title\":\"Inspect\",\"definition\":"
                        + "\"Read the target source before proposing a change\",\"dependsOn\":[],"
                        + "\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        + "\"requiredEvidenceKinds\":[\"TOOL_CALL\"],\"effectClass\":\"READ\","
                        + "\"toolRequirements\":[{\"requirementId\":\"read-sample\",\"requirementOrder\":0,"
                        + "\"toolName\":\"read_file\",\"effectClass\":\"READ\","
                        + "\"authoritySelector\":{\"kind\":\"EXACT_PATH\","
                        + "\"path\":\"src/main/java/demo/Sample.java\"},\"minSuccesses\":1,"
                        + "\"maxOccurrences\":1,\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]},"
                        + "{\"title\":\"Update\",\"definition\":\"Apply the approved scoped source change\","
                        + "\"dependsOn\":[0],\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        + "\"requiredEvidenceKinds\":[\"TOOL_CALL\",\"SNAPSHOT\"],\"effectClass\":\"WRITE\","
                        + "\"toolRequirements\":[{\"requirementId\":\"write-sample\",\"requirementOrder\":0,"
                        + "\"toolName\":\"write_file\",\"effectClass\":\"WRITE\","
                        + "\"authoritySelector\":{\"kind\":\"EXACT_PATH\","
                        + "\"path\":\"src/main/java/demo/Sample.java\",\"overwrite\":true},"
                        + "\"minSuccesses\":1,\"maxOccurrences\":1,"
                        + "\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]}]}");
    }

    private static com.paicli.provider.ModelProvider scripted(
            ConcurrentLinkedQueue<ModelResponse> responses) {
        return (messages, tools) -> {
            ModelResponse response = responses.poll();
            if (response == null) throw new IllegalStateException("provider script exhausted");
            return response;
        };
    }

    private abstract static class MultiStageApprovalReader implements ShellInputReader {
        private final InteractiveRuntimeAdapter runtime;
        private final Path workspace;
        private final Path source;
        private final String before;
        private final String taskInput;
        private final String writeDecision;
        private boolean taskSubmitted;
        private String planRequestId;
        private String writeRequestId;
        private String lastRespondedRequestId;
        private String runId;
        private int planResponses;
        private int writeResponses;

        private MultiStageApprovalReader(InteractiveRuntimeAdapter runtime, Path workspace,
                Path source, String before, String taskInput, String writeDecision) {
            this.runtime = runtime;
            this.workspace = workspace;
            this.source = source;
            this.before = before;
            this.taskInput = taskInput;
            this.writeDecision = writeDecision;
        }

        @Override public String readLine(String prompt) {
            throw new AssertionError("approval integration reader requires prompt identity");
        }

        @Override
        public String readLine(String prompt, ShellInputReader.PromptIdentity identity) {
            try {
                if (!taskSubmitted) {
                    assertEquals("IDLE", identity.inputState());
                    taskSubmitted = true;
                    return taskInput;
                }
                if ("APPROVAL_PENDING".equals(identity.inputState())) {
                    return respondToApproval(identity);
                }
                if ("IDLE".equals(identity.inputState())) {
                    assertLastRequestResolved();
                    assertTrue(runtime.awaitIdle(Duration.ofSeconds(10)));
                    return "/exit";
                }
                assertEquals("TASK_RUNNING", identity.inputState());
                assertLastRequestResolved();
                if (awaitApprovalOrIdle()) {
                    // This read began under TASK_RUNNING ownership. Return an empty line
                    // so the next read is created from the actual APPROVAL_PENDING frame.
                    return "";
                }
                assertTrue(runtime.awaitIdle(Duration.ofSeconds(10)));
                return "/exit";
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        private String respondToApproval(ShellInputReader.PromptIdentity identity) throws Exception {
            assertLastRequestResolved();
            InteractiveApprovalRequest request = runtime.pendingApproval().orElseThrow();
            assertEquals(identity.taskId(), request.taskId());
            assertFalse(request.requestId().isBlank());
            assertEquals(runtime.activeHandle().orElseThrow().taskId(), request.taskId());
            assertNotNull(request.runId());
            assertFalse(request.runId().isBlank());
            assertEquals(runtime.view().snapshot().activeRunId(), request.runId());
            if (runId == null) runId = request.runId();
            else assertEquals(runId, request.runId());

            lastRespondedRequestId = request.requestId();
            if (request.kind() == InteractiveApprovalRequest.Kind.PLAN) {
                assertEquals("advisory_plan_confirmation", request.toolName());
                assertNull(planRequestId);
                planRequestId = request.requestId();
                planResponses++;
                return "y";
            }

            assertEquals(InteractiveApprovalRequest.Kind.EDIT, request.kind());
            assertEquals("write_file", request.toolName());
            assertNotNull(planRequestId);
            assertNotEquals(planRequestId, request.requestId());
            assertNull(writeRequestId);
            writeRequestId = request.requestId();
            writeResponses++;
            assertNotNull(writeDecision);
            assertEquals(before, Files.readString(source));
            try (StateStore state = new StateStore(workspace)) {
                assertTrue(state.snapshots.findByTask(request.taskId()).isEmpty());
            }
            return writeDecision;
        }

        private boolean awaitApprovalOrIdle() throws InterruptedException {
            for (int attempt = 0; attempt < 1_000; attempt++) {
                if (runtime.pendingApproval().isPresent()) return true;
                if (runtime.activeHandle().isEmpty()) return false;
                Thread.sleep(10);
            }
            fail("timed out waiting for approval or terminal handoff");
            return false;
        }

        private void assertLastRequestResolved() {
            if (lastRespondedRequestId == null) return;
            assertTrue(runtime.pendingApproval()
                    .map(request -> !lastRespondedRequestId.equals(request.requestId()))
                    .orElse(true), "responded approval must no longer be pending");
        }

        final void assertApprovalFlow(boolean expectWrite) {
            assertEquals(1, planResponses);
            assertNotNull(planRequestId);
            assertEquals(expectWrite ? 1 : 0, writeResponses);
            if (expectWrite) {
                assertNotNull(writeRequestId);
                assertNotEquals(planRequestId, writeRequestId);
            } else {
                assertNull(writeRequestId);
            }
            assertLastRequestResolved();
            assertTrue(runtime.pendingApproval().isEmpty());
        }

        final void assertPlanlessApprovalFlow() {
            assertEquals(0,planResponses);
            assertNull(planRequestId);
            assertEquals(0,writeResponses);
            assertNull(writeRequestId);
            assertLastRequestResolved();
            assertTrue(runtime.pendingApproval().isEmpty());
        }

        @Override public void close() { }
    }

    private static final class ApprovalReader extends MultiStageApprovalReader {
        private ApprovalReader(InteractiveRuntimeAdapter runtime, Path workspace,
                Path source, String before, String decision) {
            super(runtime, workspace, source, before,
                    "Update src/main/java/demo/Sample.java value", decision);
        }
    }

    private static final class WaitingReader extends MultiStageApprovalReader {
        private WaitingReader(InteractiveRuntimeAdapter runtime) {
            this(runtime, "Inspect Sample and report the workspace");
        }

        private WaitingReader(InteractiveRuntimeAdapter runtime, String taskInput) {
            super(runtime, null, null, null, taskInput, null);
        }

    }

    private static final class CaptureShellRenderer implements InteractiveShellRenderer {
        private final List<TaskSubmissionResult> results = new ArrayList<>();
        public void startup(String version, InteractiveSession.Snapshot session) { }
        public void lines(List<String> lines) { }
        public void taskResult(TaskSubmissionResult result) { results.add(result); }
        public void inputCleared() { }
        public void warning(String message) { }
        public void error(String code, String message) { fail(code + ": " + message); }
        public void closed() { }
    }
}
