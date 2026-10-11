package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.command.*;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.mcp.config.*;
import com.paicli.mcp.tool.McpToolNames;
import com.paicli.model.*;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.skill.SkillPackageFiles;
import com.paicli.state.*;
import com.paicli.provider.ModelProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real npm services through the same shell command and task executor used by users. */
@EnabledIfSystemProperty(named="paicli.ecosystem.external",matches="true")
class McpEcosystemProductionIT {
    @TempDir Path temporary;
    private final ObjectMapper json=new ObjectMapper();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stop","length"})
    void nativeAnswerTraversesSkillPagesFixedMcpAndDurableDelivery(String finish)throws Exception {
        Path workspace=Files.createDirectory(temporary.resolve("native-"+finish));
        Path source=Files.createDirectories(workspace.resolve("download/skill"));
        Files.writeString(source.resolve("SKILL.md"),"---\nname: native-fixture\ndescription: read the dedicated invoice fixture\n---\nACTIVE_GUIDANCE_MUST_SURVIVE\n");
        Files.writeString(source.resolve("reference.md"),"FIRST_PAGE_"+"x".repeat(1990)+"LAST_PAGE_GUIDANCE");
        Path allowed=Files.createDirectories(workspace.resolve("allowed"));
        Path invoice=Files.writeString(allowed.resolve("invoice.json"),"{\"customer\":\"Northwind Demo\",\"amount\":83}");
        Path installation=Path.of(System.getProperty("paicli.ecosystem.installation")).toAbsolutePath();
        var mcp=new McpServerConfig("filesystem",true,"STDIO",Path.of(System.getProperty("paicli.ecosystem.node")),
                List.of(installation.resolve("node_modules/@modelcontextprotocol/server-filesystem/dist/index.js").toString(),allowed.toString()),
                workspace,List.of(),new McpRuntimeLimits(30000,60000,3000,16,1048576,65536,32,256,4194304,262144,262144),
                List.of("read_file"),null,List.of(installation.resolve("node_modules"),installation.resolve("package-lock.json")));
        Path configFile=workspace.resolve("mcp-import.json");json.writeValue(configFile.toFile(),new McpClientConfig(List.of(mcp)));
        var script=List.of(new ToolCall("load_skill",Map.of("name","native-fixture")),
                new ToolCall("read_skill_resource",Map.of("name","native-fixture","path","reference.md","offset",0,"max_chars",2000)),
                new ToolCall("read_skill_resource",Map.of("name","native-fixture","path","reference.md","offset",2000,"max_chars",2000)),
                new ToolCall(McpToolNames.alias("filesystem","read_file"),Map.of("path",invoice.toString())));
        var requests=new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        String answer="客户 Northwind Demo，金额 83。";
        var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));int index=requests.size()-1;
            var message=new LinkedHashMap<String,Object>();message.put("role","assistant");
            String reason=finish;
            if(index<script.size()){
                ToolCall call=script.get(index);reason="tool_calls";message.put("content","Reading fixture evidence");
                message.put("tool_calls",List.of(Map.of("id","native-call-"+index,"type","function","function",
                        Map.of("name",call.name(),"arguments",json.writeValueAsString(call.arguments())))));
            }else message.put("content",json.writeValueAsString(Map.of("userAnswer",answer)));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",reason,"message",message)),
                    "usage",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120)));
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });http.start();
        try {
            Config config=Config.defaults().merge(json.readValue("""
                    {"provider":"openai","provider_preset":"deepseek","model":"deepseek-v4-flash","thinking":"disabled",
                     "approval_mode":"on-request","enable_plan":false,"enable_verify":false,"enable_repair":false,
                     "enable_skills":true,"enable_hooks":false,"max_iterations":8,"context_window_tokens":131072}
                    """,Config.class));
            var adapter=new com.paicli.provider.OpenAICompatibleProvider("http://127.0.0.1:"+http.getAddress().getPort(),
                    "local-fixture-only",config.model(),config.effectiveSystemPrompt(),5,
                    com.paicli.provider.OpenAICompatiblePreset.from(config),"disabled",null);
            var budget=new EcosystemD7SmokeIT.Budget(workspace.resolve("budget.jsonl"),java.math.BigDecimal.ONE);
            var bounded=new EcosystemD7SmokeIT.BoundedProvider(adapter,budget,"af15-"+finish,6,6,
                    com.paicli.provider.OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE);
            Path captures=Files.createDirectory(workspace.resolve("captures"));
            var provider=new EcosystemNaturalTaskIT.CapturingProvider(bounded,captures,"after");
            var events=new CopyOnWriteArrayList<com.paicli.event.AgentEvent>();String taskId;
            try(var runtime=InteractiveRuntimeAdapter.open(workspace,
                    new ProductionInteractiveTaskExecutor(workspace,()->config,ignored->provider),events::add)){
                var session=EcosystemNaturalTaskIT.configuredSession("af15",workspace,config);session.start();runtime.openSession(session);
                var registry=new SlashCommandRegistry();
                var router=new SlashCommandRouter("test",registry,new SlashCommandParser(registry),List::of)
                        .withMcpCommands(runtime::mcpCommand).withSkillCommands(runtime::skillCommand);
                String importSkill="/skills import \""+source+"\"";
                var preview=router.route(importSkill,session,null);
                String hash=preview.lines().stream().filter(l->l.startsWith("SHA-256: ")).findFirst().orElseThrow().substring(9);
                assertEquals("SKILLS",router.route(importSkill+" "+hash,session,null).code());
                assertEquals("MCP",router.route("/mcp config import \""+configFile+"\"",session,null).code());
                var connect=router.route("/mcp connect filesystem",session,null);
                String token=connect.lines().getLast().substring(connect.lines().getLast().lastIndexOf(' ')+1);
                assertEquals("MCP",router.route("/mcp connect filesystem "+token,session,null).code());
                runtime.submit(new InteractiveTaskRequest("af15",workspace,"读取 Skill 的全部参考页和授权发票，报告客户和金额。",List.of()));
                var task=runtime.activeForegroundTask().orElseThrow();taskId=task.taskId();int approvals=0;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);Set<String> handled=new HashSet<>();
                while(!task.terminal().isDone()&&System.nanoTime()<deadline){
                    var pending=runtime.pendingApproval();
                    if(pending.isPresent()&&handled.add(pending.get().requestId())){
                        assertTrue(runtime.respondApproval(pending.get().requestId(),"y").resolved());approvals++;
                    }
                    Thread.sleep(10);
                }
                assertEquals(finish.equals("stop")?TaskSubmissionResult.Status.COMPLETED:TaskSubmissionResult.Status.FAILED,
                        task.terminal().get(1,TimeUnit.SECONDS).status(),events.toString());
                assertEquals(1,approvals);assertTrue(runtime.awaitIdle(Duration.ofSeconds(5)));
            }
            assertEquals(5,requests.size());assertEquals(5,bounded.attempts);assertFalse(budget.unknown);
            assertTrue(budget.reservations.isEmpty());
            for(var request:requests)assertEquals("json_object",request.path("response_format").path("type").asText());
            String last=requests.getLast().path("messages").toString();
            for(String expected:List.of("ACTIVE_GUIDANCE_MUST_SURVIVE","FIRST_PAGE_","LAST_PAGE_GUIDANCE","Northwind Demo","83"))
                assertTrue(last.contains(expected),"missing from actual final request: "+expected);
            assertFalse(requests.getFirst().path("messages").toString().contains("ACTIVE_GUIDANCE_MUST_SURVIVE"));
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+workspace.resolve(".paicli/state/paicli.db"))){
                try(var q=db.prepareStatement("SELECT c.coding_run_id,s.run_id FROM mcp_tool_calls c JOIN skill_loads s ON c.task_id=s.task_id WHERE c.task_id=?")){
                    q.setString(1,taskId);try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(rows.getString(1),rows.getString(2));}
                }
                for(String table:List.of("task_user_results","events")){
                    String condition=table.equals("events")?" AND type='FINAL'":"";
                    try(var q=db.prepareStatement("SELECT count(*) FROM "+table+" WHERE task_id=?"+condition)){
                        q.setString(1,taskId);try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(finish.equals("stop")?1:0,rows.getInt(1));}
                    }
                }
                try(var q=db.prepareStatement("SELECT count(*) FROM side_effect_occurrences WHERE task_id=? AND status IN ('STARTED','UNKNOWN')")){
                    q.setString(1,taskId);try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                }
            }
            Path evidence=Path.of(System.getProperty("paicli.ecosystem.evidence"));
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("af15-"+finish+".json").toFile(),
                    Map.of("taskId",taskId,"finishReason",finish,"localHttpCalls",requests.size(),"paidProviderCalls",0,
                            "requests",requests,"events",events.stream().map(Object::toString).toList()));
        } finally {http.stop(0);}
    }

    @Test void filesystemReadAndServerRangeDenialUseOneApprovalAndDurableIdentity() throws Exception {
        Path workspace=Files.createDirectory(temporary.resolve("workspace"));
        Path allowed=Files.createDirectory(workspace.resolve("allowed"));
        Path inside=Files.writeString(allowed.resolve("record.json"),"{\"invoice\":\"INV-2047\",\"amount\":83}");
        Path outside=Files.writeString(workspace.resolve("outside.txt"),"NON_SENSITIVE_OUTSIDE_FIXTURE");
        var calls=List.of(new ToolCall(McpToolNames.alias("filesystem","read_file"),Map.of("path",inside.toString())),
                new ToolCall(McpToolNames.alias("filesystem","read_file"),Map.of("path",outside.toString())));
        execute(workspace,"filesystem",List.of(allowed.toString()),List.of("read_file"),calls,
                List.of("SUCCEEDED","TOOL_ERROR"),List.of("INV-2047","Access denied"));
    }

    @Test void chromeNavigationSnapshotConsoleAndNetworkUseSameProductionComposition() throws Exception {
        Path workspace=Files.createDirectory(temporary.resolve("browser-workspace"));
        var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var requests=new AtomicInteger();
        http.createContext("/",exchange->{
            boolean api=exchange.getRequestURI().getPath().equals("/api/failure");
            if(api)requests.incrementAndGet();
            byte[] body=(api?"fixture failure":"""
                    <!doctype html><title>MCP acceptance fixture</title><h1>Invoice INV-2047</h1>
                    <input aria-label="Filter" oninput="document.querySelector('output').textContent=this.value">
                    <button onclick="document.querySelector('output').textContent='FILTER_APPLIED'">Apply</button>
                    <output>initial</output><script>console.error('FIXTURE_CONSOLE_ERROR');fetch('/api/failure');</script>
                    """).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",api?"text/plain":"text/html; charset=utf-8");
            exchange.sendResponseHeaders(api?503:200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        http.start();
        try {
            var calls=List.of(new ToolCall(McpToolNames.alias("chrome","new_page"),Map.of("url","http://127.0.0.1:"+http.getAddress().getPort())),
                    new ToolCall(McpToolNames.alias("chrome","take_snapshot"),Map.of("pageId",-1)),
                    new ToolCall(McpToolNames.alias("chrome","fill"),Map.of("pageId",-1,"uid","__FILTER__","value","INV-2047","includeSnapshot",true)),
                    new ToolCall(McpToolNames.alias("chrome","click"),Map.of("pageId",-1,"uid","__APPLY__","includeSnapshot",true)),
                    new ToolCall(McpToolNames.alias("chrome","list_console_messages"),Map.of("pageId",-1)),
                    new ToolCall(McpToolNames.alias("chrome","list_network_requests"),Map.of("pageId",-1)));
            execute(workspace,"chrome",List.of("--headless","--isolated","--no-usage-statistics","--no-performance-crux",
                            "--executablePath="+System.getProperty("paicli.ecosystem.chrome")),
                    List.of("new_page","take_snapshot","fill","click","list_console_messages","list_network_requests"),calls,
                    Collections.nCopies(6,"SUCCEEDED"),List.of("INV-2047","FILTER_APPLIED","FIXTURE_CONSOLE_ERROR","503"));
            assertEquals(1,requests.get());
        } finally {http.stop(0);}
    }

    private void execute(Path workspace,String id,List<String> extra,List<String> allowlist,
            List<ToolCall> script,List<String> expectedStatuses,List<String> expectedEvidence) throws Exception {
        execute(workspace,id,extra,allowlist,script,expectedStatuses,expectedEvidence,null);
    }

    record Composition(Path skillSource,String verificationCommand,String expectedVerification,String label) { }

    void execute(Path workspace,String id,List<String> extra,List<String> allowlist,
            List<ToolCall> script,List<String> expectedStatuses,List<String> expectedEvidence,Composition composition) throws Exception {
        var jointEvidence=new LinkedHashMap<String,Object>();
        Path installation=Path.of(System.getProperty("paicli.ecosystem.installation")).toAbsolutePath();
        Path entry=installation.resolve("node_modules").resolve(id.equals("filesystem")
                ?"@modelcontextprotocol/server-filesystem/dist/index.js":"chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js");
        var args=new ArrayList<String>();args.add(entry.toString());args.addAll(extra);
        var server=new McpServerConfig(id,true,"STDIO",Path.of(System.getProperty("paicli.ecosystem.node")),
                args,workspace,List.of(),new McpRuntimeLimits(30000,60000,3000,16,1048576,65536,32,256,4194304,262144,262144),
                allowlist,null,List.of(installation.resolve("node_modules"),installation.resolve("package-lock.json")));
        Path configFile=workspace.resolve("import.json");json.writeValue(configFile.toFile(),new McpClientConfig(List.of(server)));
        AtomicInteger step=new AtomicInteger();var observed=new CopyOnWriteArrayList<String>();
        ModelProvider provider=(messages,definitions)->{
            observed.addAll(messages.stream().map(AgentMessage::content).filter(Objects::nonNull).toList());
            int index=step.getAndIncrement();
            if(index<script.size()) {
                assertTrue(definitions.stream().anyMatch(d->d.name().equals(script.get(index).name())),definitions.toString());
                ToolCall next=script.get(index);
                if(id.equals("chrome")&&next.arguments().containsKey("pageId")) {
                    String prior=String.join("\n",observed);
                    var page=java.util.regex.Pattern.compile("(?m)^(\\d+):.*MCP acceptance fixture.*$").matcher(prior);
                    assertTrue(page.find(),"page identity absent: "+prior);
                    var parameters=new HashMap<String,Object>(next.arguments());parameters.put("pageId",Integer.parseInt(page.group(1)));
                    if(parameters.containsKey("uid")) {
                        String role=parameters.get("uid").equals("__FILTER__")?"textbox \\\"Filter\\\"":"button \\\"Apply\\\"";
                        var element=java.util.regex.Pattern.compile("uid=(\\S+) "+role).matcher(prior);
                        String uid=null;while(element.find())uid=element.group(1);
                        assertNotNull(uid,"element identity absent: "+prior);parameters.put("uid",uid);
                    }
                    next=new ToolCall(next.name(),parameters);
                }
                return ModelResponse.toolCallResponse("Perform fixture operation",next);
            }
            return ModelResponse.finalAnswer(composition==null?"Fixture operations recorded; consult the tool evidence for failures.":"All checks passed. The browser task succeeded.");
        };
        Config baseConfig=Config.defaults().merge(json.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":12,"context_window_tokens":131072}
                """,Config.class));
        Config config=composition==null?baseConfig:baseConfig.merge(json.readValue("{\"enable_skills\":true}",Config.class))
                .withVerification(composition.verificationCommand(),true);
        var executor=new ProductionInteractiveTaskExecutor(workspace,()->config,ignored->provider);
        var events=new CopyOnWriteArrayList<com.paicli.event.AgentEvent>();
        String task;
        int approvals=0;
        var baseline=ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        var owned=new ArrayList<ProcessHandle>();
        try(var runtime=InteractiveRuntimeAdapter.open(workspace,executor,events::add)) {
            var session=new InteractiveSession("ecosystem-"+id,workspace,config.effectiveProvider(),config.model(),
                    Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN,Instant.now(),
                    composition==null?"off":"configured",composition==null?"none":"configured verifier");session.start();runtime.openSession(session);
            var registry=new SlashCommandRegistry();
            var router=new SlashCommandRouter("test",registry,new SlashCommandParser(registry),List::of,
                    new StateStoreInteractiveQueryFacade(workspace,runtime.view()))
                    .withMcpCommands(runtime::mcpCommand).withSkillCommands(runtime::skillCommand);
            if(composition!=null){
                String command="/skills import \""+composition.skillSource()+"\"";
                var previewSkill=router.route(command,session,null);assertEquals("SKILLS",previewSkill.code(),previewSkill.toString());
                String hash=previewSkill.lines().stream().filter(l->l.startsWith("SHA-256: ")).findFirst().orElseThrow().substring(9);
                var importedSkill=router.route(command+" "+hash,session,null);assertEquals("SKILLS",importedSkill.code(),importedSkill.toString());
                assertEquals(hash,SkillPackageFiles.snapshot(workspace.resolve(".paicli/skills/browser-regression")).hash());
                jointEvidence.put("skillPackageHash",hash);
            }
            // Commands use the production router and runtime; no low-level trusted start call.
            var imported=router.route("/mcp config import \""+configFile+"\"",session,null);
            assertEquals("MCP",imported.code(),imported.toString());
            assertTrue(runtime.mcpCommand(List.of("status"),session).lines().getFirst().contains("disconnected"));
            var preview=router.route("/mcp connect "+id,session,null);
            assertEquals("MCP",preview.code(),preview.toString());
            String confirmation=preview.lines().getLast().substring(preview.lines().getLast().lastIndexOf(' ')+1);
            assertTrue(confirmation.matches("[a-f0-9]{64}"));
            var connected=router.route("/mcp connect "+id+" "+confirmation,session,null);
            assertEquals("MCP",connected.code(),connected.toString());
            assertTrue(runtime.mcpCommand(List.of("tools"),session).lines().stream().anyMatch(l->l.contains("[EXPOSED]")));
            runtime.submit(new InteractiveTaskRequest(session.snapshot().sessionId(),workspace,"Inspect only the dedicated MCP fixture",List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();task=foreground.taskId();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(240);
            Set<String> handled=new HashSet<>();
            while(!foreground.terminal().isDone() && System.nanoTime()<deadline) {
                var pending=runtime.pendingApproval();
                if(pending.isPresent()&&handled.add(pending.get().requestId())) {
                    assertEquals("MCP_ERROR",runtime.mcpCommand(List.of("disconnect"),session).code());
                    assertTrue(runtime.respondApproval(pending.get().requestId(),"y").resolved());approvals++;
                }
                Thread.sleep(20);
            }
            var terminal=foreground.terminal().get(1,TimeUnit.SECONDS);
            var expectedTerminal=composition==null?TaskSubmissionResult.Status.COMPLETED:
                    composition.expectedVerification().equals("PASSED")?TaskSubmissionResult.Status.COMPLETED_VERIFIED:TaskSubmissionResult.Status.FAILED;
            assertEquals(expectedTerminal,terminal.status(),terminal+" events="+events);
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(expectedStatuses.size(),approvals,"exactly one prompt per MCP call; Skill activation grants no permission");
            if(composition!=null){
                var report=router.route("/report "+task,session,null);assertEquals("REPORT",report.code(),report.toString());
                jointEvidence.put("report",report.lines());
            }
            for(String expected:expectedEvidence)assertTrue(observed.stream().anyMatch(s->s.contains(expected)),"missing "+expected+" observed="+observed);
            owned.addAll(ProcessHandle.current().descendants().filter(p->!baseline.contains(p.pid())).toList());
            assertEquals("MCP",runtime.mcpCommand(List.of("disconnect"),session).code());
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+workspace.resolve(".paicli/state/paicli.db"));var query=db.prepareStatement(
                "SELECT c.*,r.task_id AS run_task FROM mcp_tool_calls c JOIN coding_runs r ON r.run_id=c.coding_run_id WHERE c.task_id=? ORDER BY c.started_at,c.rowid")) {
            query.setString(1,task);var records=new ArrayList<Map<String,String>>();
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    assertEquals(task,rows.getString("run_task"));
                    assertTrue(rows.getString("occurrence_key").startsWith("occ_"));
                    assertEquals("APPROVED",rows.getString("approval_decision"));
                    assertNotNull(rows.getString("request_id"));
                    records.add(Map.of("task",task,"run",rows.getString("coding_run_id"),"occurrence",rows.getString("occurrence_key"),
                            "call",rows.getString("mcp_tool_call_id"),"status",rows.getString("status"),"tool",rows.getString("original_tool_name")));
                }
            }
            assertEquals(expectedStatuses,records.stream().map(r->r.get("status")).toList());
            if(composition!=null){
                String run=records.getFirst().get("run");
                assertTrue(records.stream().allMatch(r->r.get("run").equals(run)));
                try(var state=new StateStore(workspace)){
                    var loads=state.skillLoads.findByStatus(SkillLoadStore.STATUS_LOADED).stream().filter(l->l.taskId().equals(task)).toList();
                    assertEquals(1,loads.size());assertEquals(run,loads.getFirst().runId());
                    var accesses=state.skillLoads.accessesForRun(run);
                    assertTrue(accesses.stream().anyMatch(a->a.kind().equals("READ")&&a.resourcePath().equals("references/report.md")&&a.occurrenceId().startsWith("occ_")));
                    var verifications=state.verifications.findByRun(run);assertEquals(1,verifications.size());
                    var verification=verifications.getFirst();assertEquals(task,verification.taskId());
                    assertEquals(composition.expectedVerification(),verification.status());
                    assertEquals(composition.verificationCommand(),verification.command());
                    assertNotNull(verification.candidateDigest());
                    assertEquals(composition.expectedVerification().equals("PASSED")?0:1,verification.exitCode());
                    var taskRecord=state.tasks.findById(task).orElseThrow();
                    if(composition.expectedVerification().equals("FAILED"))assertNotEquals(TaskStore.STATUS_COMPLETED_VERIFIED,taskRecord.status());
                    String report=jointEvidence.get("report").toString();
                    for(String expected:List.of("browser-regression","references/report.md",composition.expectedVerification(),
                            task.substring(0,7)+"…"+task.substring(task.length()-8),run.substring(0,7)+"…"+run.substring(run.length()-8)))
                        assertTrue(report.contains(expected),"missing report fact "+expected+": "+report);
                    jointEvidence.put("skillLoads",loads);jointEvidence.put("skillAccesses",accesses);
                    jointEvidence.put("verification",verification);jointEvidence.put("taskStatus",taskRecord.status());
                }
            }
            Path evidence=Path.of(System.getProperty("paicli.ecosystem.evidence"));
            jointEvidence.putAll(Map.of("intent","SMOKE","paidProviderCalls",0,"approvals",approvals,"calls",records,"observed",observed));
            json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve((composition==null?id:composition.label())+"-production.json").toFile(),jointEvidence);
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline && ProcessHandle.current().descendants().anyMatch(p->!baseline.contains(p.pid())&&p.isAlive()))Thread.sleep(50);
        assertTrue(ProcessHandle.current().descendants().noneMatch(p->!baseline.contains(p.pid())&&p.isAlive()),"MCP or browser child survived close");
        assertTrue(owned.stream().noneMatch(ProcessHandle::isAlive),"captured MCP/browser PID survived close or was reparented");
    }
}
