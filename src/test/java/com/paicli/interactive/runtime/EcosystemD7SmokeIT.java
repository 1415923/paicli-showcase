package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.interactive.command.*;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.mcp.config.*;
import com.paicli.model.*;
import com.paicli.provider.*;
import com.paicli.provider.telemetry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit SMOKE only. Real ProviderFactory, production task entry, manual host approvals.
 * No default paid execution, no task replacement, and no synthetic model responses. */
@EnabledIfSystemProperty(named="paicli.d7.mode", matches="preflight|paid")
class EcosystemD7SmokeIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final BigDecimal LIMIT = new BigDecimal("20");
    // Peak Flash tariff: <= 1.1M input tokens * 3 + production max 32768 output * 9 / 1M.
    // Reserve for EVERY HTTP attempt, including retries; unknown usage retains reserve and stops.
    private static final BigDecimal RESERVE = new BigDecimal("3.60");
    private final Path project = Path.of("").toAbsolutePath();
    private final Path change = project.getParent().resolve(".forgekit/changes/v1.3.0-mcp-skills-ecosystem");
    private final Path evidence = change.resolve("evidence/d7");

    @Test
    @EnabledIfSystemProperty(named="paicli.d7.fixture",matches=".+")
    void originalHelperUsesProductionCleanup(@org.junit.jupiter.api.io.TempDir Path work) throws Exception {
        try(var probe=new java.net.ServerSocket()) { probe.bind(new java.net.InetSocketAddress("127.0.0.1",18797)); }
        Path page=Files.createDirectory(work.resolve("page"));
        Path original=project.resolve("target/d7/E4");
        Files.copy(original.resolve("page/index.html"),page.resolve("index.html"));
        String server=Files.readString(original.resolve("page/server.py"));
        server=server.replace("HTTPServer(('127.0.0.1',args.port),Handler).serve_forever()",
                "import os\nPath(__file__).with_name('server.pid').write_text(str(os.getpid()))\nHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()");
        Files.writeString(page.resolve("server.py"),server);
        Files.copy(original.resolve("automation.py"),work.resolve("automation.py"));
        String python=change.resolve("evidence/d4-d5/py/Scripts/python.exe").toString();
        String helper=change.resolve("evidence/d1/sources/anthropics-skills/skills/webapp-testing/scripts/with_server.py").toString();
        var executed=new com.paicli.runtime.BoundedProcessExecutor(65536).execute(
                new ProcessBuilder(python,helper,"--server",python+" page/server.py --port 18797","--port","18797","--",python,"automation.py")
                        .directory(work.toFile()),Duration.ofSeconds(45));
        var record=new LinkedHashMap<String,Object>();record.put("intent","DIAGNOSTIC");record.put("paidProviderCalls",0);record.put("process",executed);
        Path pid=page.resolve("server.pid");
        try {
            assertEquals(com.paicli.runtime.BoundedProcessExecutor.Status.SUCCEEDED,executed.status(),executed.toString());
            assertTrue(Files.isRegularFile(pid),"The helper must start this task's own server");
            long serverPid=Long.parseLong(Files.readString(pid));record.put("serverPid",serverPid);
            assertFalse(ProcessHandle.of(serverPid).map(ProcessHandle::isAlive).orElse(false));
            var browser=JSON.readTree(Files.readString(work.resolve("playwright-result.json")));
            assertEquals("INV-2047 | Northwind Demo | 83",browser.path("result").asText());
            record.put("browser",browser);record.put("requests",Files.readAllLines(page.resolve("requests.jsonl")));
            assertTrue(Files.readString(page.resolve("requests.jsonl")).contains("/api/risk-check?invoice=INV-2047"));
            try(var probe=new java.net.ServerSocket()) { probe.bind(new java.net.InetSocketAddress("127.0.0.1",18797)); }
            record.put("ownedServerStartedAndStopped",true);record.put("portReleased",true);record.put("status","PASS");
        } finally {
            if(Files.isRegularFile(pid)) ProcessHandle.of(Long.parseLong(Files.readString(pid))).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            writeNew(Path.of(System.getProperty("paicli.d7.fixture")),record);
        }
    }

    @Test
    @EnabledIfSystemProperty(named="paicli.d7.mode",matches="preflight")
    void budgetControlsUseNoProvider(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        String digest="a".repeat(64);
        var interrupted=new Budget(temporary.resolve("interrupted.jsonl"));
        for(int i=0;i<5;i++)interrupted.reserve("E1",digest);
        assertThrows(IllegalStateException.class,()->interrupted.reserve("E1",digest));
        var recovered=new Budget(temporary.resolve("interrupted.jsonl"));
        assertThrows(IllegalStateException.class,()->recovered.reserve("E2",digest));
        var missing=new Budget(temporary.resolve("missing.jsonl"));
        missing.settle(missing.reserve("E1",digest),ProviderCallObservation.notExposed());
        assertEquals(RESERVE,missing.spent);
        assertThrows(IllegalStateException.class,()->missing.reserve("E1",digest));
        var complete=new Budget(temporary.resolve("complete.jsonl"));
        String first=complete.reserve("E1",digest);
        var usage=new ProviderCallObservation(1000,100,1100,600,null,null,
                ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE,null,null,null,null,null,false);
        complete.settle(first,usage);
        assertEquals(0,new BigDecimal("0.002160").compareTo(complete.spent));
        String retry=complete.reserve("E1",digest);assertNotEquals(first,retry);
        complete.settle(retry,usage);
        var reload=new Budget(temporary.resolve("complete.jsonl"));
        assertEquals(0,new BigDecimal("0.004320").compareTo(reload.spent));
        var inconsistent=new Budget(temporary.resolve("inconsistent.jsonl"));
        var invalid=new ProviderCallObservation(1000,100,1100,1001,null,null,
                ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE,null,null,null,null,null,false);
        assertThrows(IllegalStateException.class,()->inconsistent.settle(inconsistent.reserve("E1",digest),invalid));
        try(var channel=FileChannel.open(temporary.resolve("lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.lock();var competing=FileChannel.open(temporary.resolve("lock"),StandardOpenOption.WRITE)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,competing::tryLock);
        }
    }

    @Test void explicitTaskOrZeroCallPreflight() throws Exception {
        Config config = Config.defaults().merge(JSON.readValue("""
                {"provider":"openai","provider_preset":"deepseek","model":"deepseek-v4-flash",
                 "base_url":"https://api.deepseek.com","api_key_env":"DEEPSEEK_API_KEY",
                 "thinking":"disabled","approval_mode":"on-request","enable_plan":false,
                 "enable_verify":false,"enable_repair":false,"enable_skills":true,
                 "enable_hooks":false,"max_iterations":24,"context_window_tokens":131072,
                 "command_timeout_seconds":120}
                """, Config.class));
        var credential = ProviderCredentialResolver.systemDefault().status(config);
        var readiness = new LinkedHashMap<String,Object>();
        readiness.put("intent", "DIAGNOSTIC");
        readiness.put("provider",config.effectiveProvider()); readiness.put("model",config.model());
        readiness.put("credential",credential); readiness.put("paidTasksStartedByPreflight",0);
        readiness.put("paidProviderCallsByPreflight",0);
        readiness.put("modelMode","thinking disabled; production output cap 32768");
        readiness.put("maximumTasks",6); readiness.put("budgetCny",LIMIT);
        readiness.put("perAttemptReserveCny",RESERVE);
        readiness.put("priceSource","https://api-docs.deepseek.com/zh-cn/quick_start/pricing/");
        readiness.put("tariffBasis","2026-09-07 peak-price conservative bound, not provider invoice");
        readiness.put("sourcesPresent",Files.isDirectory(evidence.resolve("fixtures")));
        for(int number=1;number<=6;number++) {
            Path preparedWorkspace=project.resolve("target/d7/E"+number);
            new InteractiveTaskRequest("preflight",preparedWorkspace,taskInput(preparedWorkspace),List.of());
        }
        if ("preflight".equals(System.getProperty("paicli.d7.mode"))) {
            Path out=Path.of(System.getProperty("paicli.d7.preflight"));
            writeNew(out,readiness);
            assertTrue(Files.isDirectory(evidence.resolve("fixtures")));
            System.out.println("D7_PREFLIGHT credentialPresent="+credential.present()+" calls=0 tasks=0");
            return;
        }
        assertTrue(credential.present(),"D7_CREDENTIAL_REQUIRED: zero task admission");
        String id=System.getProperty("paicli.d7.task","");
        assertTrue(Set.of("E1","E2","E3","E4","E5","E6").contains(id));
        Path work=project.resolve("target/d7").resolve(id);
        Path control=evidence.resolve("runs").resolve(id);
        assertTrue(Files.isDirectory(work),"Prepare and hash dedicated task workspace first");
        assertTrue(Files.isRegularFile(work.resolve("TASK.md")),"Frozen task prompt required");
        Files.createDirectories(evidence.resolve("runs"));
        try (var channel=FileChannel.open(evidence.resolve("paid.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             var lock=channel.tryLock()) {
            assertNotNull(lock,"Another D7 paid process is active");
            boolean beforeTaskRecovery=Boolean.getBoolean("paicli.d7.beforeTaskRecovery");
            long continuation=0;
            if(beforeTaskRecovery)try(var files=Files.list(control)) {
                continuation=files.filter(path->path.getFileName().toString().startsWith("before-task-continuation")).count()+1;
            }
            Path resultFile=control.resolve(beforeTaskRecovery?"continuation-result-"+continuation+".json":"result.json");
            if(beforeTaskRecovery) {
                assertTrue(Files.isRegularFile(control.resolve("result.json")));
                assertFalse(Files.exists(control.resolve("task.json")),"A submitted task cannot be replaced");
                assertEquals(0,JSON.readTree(Files.readString(control.resolve("result.json"))).path("attempts").asInt(-1));
                try(var files=Files.list(control)) {
                    for(Path prior:files.filter(path->path.getFileName().toString().startsWith("continuation-result")).toList())
                        assertEquals(0,JSON.readTree(Files.readString(prior)).path("attempts").asInt(-1));
                }
                assertFalse(Files.exists(resultFile));
                Path ledger=evidence.resolve("budget-attempts.jsonl");
                if(Files.exists(ledger))for(String line:Files.readAllLines(ledger))
                    assertNotEquals(id,JSON.readTree(line).path("task").asText(),"An attempted Provider request prohibits this continuation");
                try(var database=java.sql.DriverManager.getConnection("jdbc:sqlite:"+work.resolve(".paicli/state/paicli.db"));
                    var query=database.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM tasks")) {
                    assertTrue(rows.next());assertEquals(0,rows.getInt(1),"A durable task already exists");
                }
                writeNew(control.resolve("before-task-continuation-"+continuation+".json"),Map.of("id",id,"reason","Proven zero-task/zero-Provider preparation continuation","startedAt",Instant.now().toString()));
            } else {
                assertFalse(Files.exists(control),"Task already admitted; no paid rerun/replacement");
                Files.createDirectory(control);
                writeNew(control.resolve("started.json"),Map.of("id",id,"intent","SMOKE","startedAt",Instant.now().toString(),"workspace",work.toString()));
            }
            var budget=new Budget(evidence.resolve("budget-attempts.jsonl"));
            var provider=new BoundedProvider((OpenAICompatibleProvider)ProviderFactory.create(config),budget,id);
            final Config taskConfig=id.equals("E6")?config.withVerification("verify.cmd",true):config;
            var executor=new ProductionInteractiveTaskExecutor(work,()->taskConfig,ignored->provider);
            var result=new LinkedHashMap<String,Object>();
            result.put("id",id);result.put("intent","SMOKE");result.put("status","INTERRUPTED");
            try(var runtime=InteractiveRuntimeAdapter.open(work,executor,event->{ })) {
                var session=new InteractiveSession("d7-"+id,work,config.effectiveProvider(),config.model(),
                        Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN,Instant.now(),
                        id.equals("E6")?"configured":"off",id.equals("E6")?"configured verifier":"none");
                session.start();runtime.openSession(session);
                var registry=new SlashCommandRegistry();
                var router=new SlashCommandRouter("d7",registry,new SlashCommandParser(registry),List::of,
                        new StateStoreInteractiveQueryFacade(work,runtime.view()))
                        .withMcpCommands(runtime::mcpCommand).withSkillCommands(runtime::skillCommand);
                List<String> skills=JSON.readValue(Files.readString(work.resolve("skills.json")),
                        JSON.getTypeFactory().constructCollectionType(List.class,String.class));
                for(String source:skills) {
                    String command="/skills import \""+source+"\"";
                    var preview=router.route(command,session,null);assertEquals("SKILLS",preview.code(),preview.toString());
                    String hash=preview.lines().stream().filter(line->line.startsWith("SHA-256: ")).findFirst().orElseThrow().substring(9);
                    var imported=router.route(command+" "+hash,session,null);assertEquals("SKILLS",imported.code(),imported.toString());
                }
                String server=id.equals("E2")?"filesystem":Set.of("E1","E5").contains(id)?"chrome":null;
                if(server!=null) {
                    if(beforeTaskRecovery&&Files.isRegularFile(work.resolve(".paicli/mcp.json"))) {
                        var loader=new McpConfigLoader(work,true);
                        assertEquals(loader.load(work.resolve("mcp-import.json")),loader.load(work.resolve(".paicli/mcp.json")),"Existing imported MCP config drift");
                    } else {
                        var imported=router.route("/mcp config import \""+work.resolve("mcp-import.json")+"\"",session,null);
                        assertEquals("MCP",imported.code(),imported.toString());
                    }
                    var preview=router.route("/mcp connect "+server,session,null);
                    assertEquals("MCP",preview.code(),preview.toString());
                    String confirmation=preview.lines().getLast().replaceFirst("^.* ","");
                    assertTrue(confirmation.matches("[a-f0-9]{64}"));
                    var connected=router.route("/mcp connect "+server+" "+confirmation,session,null);
                    assertEquals("MCP",connected.code(),connected.toString());
                }
                runtime.submit(new InteractiveTaskRequest(session.snapshot().sessionId(),work,taskInput(work),List.of()));
                var foreground=runtime.activeForegroundTask().orElseThrow();
                result.put("taskId",foreground.taskId());
                writeNew(control.resolve("task.json"),Map.of("taskId",foreground.taskId(),"workspace",work.toString()));
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);
                Set<String> presented=new HashSet<>();
                while(!foreground.terminal().isDone()&&System.nanoTime()<deadline) {
                    var pending=runtime.pendingApproval();
                    if(pending.isPresent()) {
                        var request=pending.get();
                        String key=request.requestId();
                        assertTrue(key.matches("[A-Za-z0-9_-]+"));
                        if(presented.add(key)) {
                            writeNew(control.resolve("approval-"+key+".json"),request);
                            System.out.println("D7_APPROVAL "+id+" "+key+" "+request.toolName());
                        }
                        Path response=control.resolve("response-"+key+".txt");
                        if(Files.isRegularFile(response)) {
                            String answer=Files.readString(response).trim();
                            assertTrue(Set.of("y","n").contains(answer));
                            assertTrue(runtime.respondApproval(key,answer).resolved());
                        }
                    }
                    Thread.sleep(100);
                }
                var terminal=foreground.terminal().get(1,TimeUnit.SECONDS);
                result.put("status",terminal.status().name());result.put("terminal",terminal);
                assertTrue(runtime.awaitIdle(Duration.ofSeconds(10)));
                result.put("report",router.route("/report "+foreground.taskId(),session,null).lines());
                if(server!=null)result.put("disconnect",runtime.mcpCommand(List.of("disconnect"),session).code());
            } catch(Exception error) {
                result.put("errorClass",error.getClass().getSimpleName());throw error;
            } finally {
                result.put("finishedAt",Instant.now().toString());result.put("attempts",provider.attempts);
                writeNew(resultFile,result);
            }
        }
    }

    private static void writeNew(Path path,Object value) throws Exception {
        Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);
    }

    private static String taskInput(Path workspace) throws java.io.IOException {
        return Files.readString(workspace.resolve("TASK.md")).replace("\r\n","\n");
    }

    /** Retains production telemetry/transport interfaces, including the existing retry policy. */
    static final class BoundedProvider implements PreparedTurnProvider,ProviderObservationSource,
            ProviderHttpStatusSource,ProviderTransportEvidenceSource,ProviderRetrySignalSource {
        final OpenAICompatibleProvider delegate;final Budget budget;final String task;
        @Override public boolean usesManagedAnswerEnvelope(){return delegate.usesManagedAnswerEnvelope();}
        final OutputRequirement.Mode outputMode;
        @Override public OutputRequirement.Mode managedOutputMode(){return outputMode;}
        @Override public void validateOutputRequirement(OutputRequirement requirement, boolean hasTools){
            delegate.validateOutputRequirement(requirement,hasTools);
        }
        final ThreadLocal<ProviderCallObservation> observation=new ThreadLocal<>();int attempts;
        int maxRequests=Integer.MAX_VALUE,maxToolCalls=Integer.MAX_VALUE,proposedToolCalls;
        BoundedProvider(OpenAICompatibleProvider delegate,Budget budget,String task){
            this.delegate=delegate;this.budget=budget;this.task=task;
            this.outputMode=OutputRequirement.Mode.PROMPT_ONLY;
        }
        BoundedProvider(OpenAICompatibleProvider delegate,Budget budget,String task,int requests,int tools){
            this(delegate,budget,task);this.maxRequests=requests;this.maxToolCalls=tools;
        }
        /** Explicit candidate qualification; no system property or production-wide capability claim. */
        BoundedProvider(OpenAICompatibleProvider delegate,Budget budget,String task,int requests,int tools,
                OutputRequirement.Mode outputMode){
            this.delegate=Objects.requireNonNull(delegate);this.budget=Objects.requireNonNull(budget);
            this.task=Objects.requireNonNull(task);this.outputMode=Objects.requireNonNull(outputMode);
            if(requests<1||requests>24||tools<1||tools>24||outputMode==OutputRequirement.Mode.LEGACY)
                throw new IllegalArgumentException("BOUNDED_OUTPUT_QUALIFICATION_REQUIRED");
            this.maxRequests=requests;this.maxToolCalls=tools;
        }
        public PreparedProviderTurn prepareTurn(String operation,List<AgentMessage> messages,List<ToolDefinition> tools) {
            return prepareTurn(operation,messages,tools,OutputRequirement.LEGACY);
        }
        public PreparedProviderTurn prepareTurn(String operation,List<AgentMessage> messages,
                List<ToolDefinition> tools,OutputRequirement requirement) {
            var prepared=delegate.prepareTurn(operation,messages,tools,requirement);
            BigDecimal reserve=maxRequests==Integer.MAX_VALUE?RESERVE:
                    DeliveryRecheckLimits.requestReserve(prepared);
            return new PreparedProviderTurn(prepared.requestDigest(),prepared.metrics(),()->{
                String reservation=budget.reserve(task,prepared.requestDigest(),reserve,maxRequests);attempts++;
                var response=prepared.execute();
                var observed=delegate.takeObservation();observation.set(observed);
                budget.settle(reservation,observed);
                proposedToolCalls+=response.toolCalls().size();
                if(proposedToolCalls>maxToolCalls)throw new IllegalStateException("RECHECK_TOOL_LIMIT");
                return response;
            });
        }
        public ProviderCallObservation takeObservation(){var value=observation.get();observation.remove();return value;}
        public Integer takeHttpStatus(){return delegate.takeHttpStatus();}
        public ProviderTransportEvidence takeTransportEvidence(){return delegate.takeTransportEvidence();}
        public ProviderRetrySignal takeRetrySignal(){return delegate.takeRetrySignal();}
    }

    static final class Budget {
        final Path ledger;final BigDecimal limit;BigDecimal spent=BigDecimal.ZERO;boolean unknown;
        final Map<String,BigDecimal> reservations=new HashMap<>();
        final Map<String,Integer> requestCounts=new HashMap<>();
        Budget(Path ledger)throws Exception {
            this(ledger,LIMIT);
        }
        Budget(Path ledger,BigDecimal limit)throws Exception {
            if(limit.signum()<=0||limit.compareTo(LIMIT)>0)throw new IllegalArgumentException("Invalid diagnostic budget");
            this.ledger=ledger;this.limit=limit;
            if(Files.exists(ledger)) {
                Set<String> outstanding=new HashSet<>();
                for(String line:Files.readAllLines(ledger)) {
                    var record=JSON.readTree(line);String id=record.path("reservation").asText();
                    if(record.path("kind").asText().equals("RESERVED")){
                        BigDecimal amount=new BigDecimal(record.path("reserveCny").asText());
                        outstanding.add(id);reservations.put(id,amount);spent=spent.add(amount);
                        requestCounts.merge(record.path("task").asText(),1,Integer::sum);
                    }
                    else if(record.path("kind").asText().equals("SETTLED")){
                        assertTrue(outstanding.remove(id));spent=spent.subtract(reservations.remove(id)).add(new BigDecimal(record.path("peakCostUpperCny").asText()));
                    } else if(record.path("kind").asText().equals("UNKNOWN"))unknown=true;
                }
                unknown|=!outstanding.isEmpty();
            }
        }
        synchronized String reserve(String task,String digest) {
            return reserve(task,digest,RESERVE,Integer.MAX_VALUE);
        }
        synchronized String reserve(String task,String digest,BigDecimal amount,int maxRequests) {
            if(amount.signum()<=0||amount.compareTo(RESERVE)>0||maxRequests<1)
                throw new IllegalArgumentException("Invalid bounded reservation");
            if(requestCounts.getOrDefault(task,0)>=maxRequests)throw new IllegalStateException("RECHECK_HTTP_LIMIT");
            if(unknown||spent.add(amount).compareTo(limit)>0)throw new IllegalStateException("D7_BUDGET_STOP");
            String id=UUID.randomUUID().toString();
            append(Map.of("kind","RESERVED","reservation",id,"task",task,"requestDigest",digest,"reserveCny",amount));
            reservations.put(id,amount);requestCounts.merge(task,1,Integer::sum);
            spent=spent.add(amount);return id;
        }
        synchronized void settle(String id,ProviderCallObservation observation) {
            BigDecimal reserved=reservations.get(id);
            if(reserved==null)throw new IllegalStateException("UNKNOWN_OR_SETTLED_RESERVATION");
            if(observation==null||observation.inputTokens()==null||observation.outputTokens()==null) {
                unknown=true;append(Map.of("kind","UNKNOWN","reservation",id));return;
            }
            int cache=observation.cacheReadTokens()==null?0:observation.cacheReadTokens();
            int input=observation.inputTokens(),output=observation.outputTokens();
            if(cache>input||input>1_100_000||output>32768){
                unknown=true;append(Map.of("kind","UNKNOWN","reservation",id,"reason","PRICE_BOUND_INVALID"));
                throw new IllegalStateException("D7_PRICE_BOUND_INVALID");
            }
            BigDecimal cost=BigDecimal.valueOf(input-cache).multiply(new BigDecimal("3"))
                    .add(BigDecimal.valueOf(cache).multiply(new BigDecimal("0.10")))
                    .add(BigDecimal.valueOf(output).multiply(new BigDecimal("9"))).movePointLeft(6);
            if(cost.compareTo(reserved)>0){unknown=true;append(Map.of("kind","UNKNOWN","reservation",id,
                    "reason","RESERVATION_BOUND_EXCEEDED"));throw new IllegalStateException("D7_PRICE_BOUND_INVALID");}
            append(Map.of("kind","SETTLED","reservation",id,"peakCostUpperCny",cost,"usage",observation));
            reservations.remove(id);spent=spent.subtract(reserved).add(cost);
        }
        void append(Object row) {
            try(var channel=FileChannel.open(ledger,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
                var bytes=java.nio.ByteBuffer.wrap((JSON.writeValueAsString(row)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            } catch(Exception error){throw new IllegalStateException("D7_LEDGER_WRITE_FAILED",error);}
        }
    }
}
