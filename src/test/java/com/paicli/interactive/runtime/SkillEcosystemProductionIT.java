package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.command.*;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import com.paicli.skill.*;
import com.paicli.state.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SkillEcosystemProductionIT {
    @TempDir Path workspace;
    @ParameterizedTest @ValueSource(strings={"model","explicit","unknown","deny","changed","nonzero","missing","long-error","timeout","cancel"})
    void officialTaskChainKeepsDiscoveryApprovalAndEvidenceBound(String scenario)throws Exception {
        Path source=Files.createDirectories(workspace.resolve("download/original"));
        Files.writeString(source.resolve("SKILL.md"),"---\nname: fixture-skill\ndescription: fixture package discovery\nallowed-tools: run_command\n---\nBODY_ONLY_AFTER_ACTIVATION\n");
        Files.writeString(source.resolve("reference.md"),"RESOURCE_ONLY_AFTER_READ");
        var calls=new ArrayList<ToolCall>();
        if(!scenario.equals("explicit"))calls.add(new ToolCall("load_skill",Map.of("name","fixture-skill")));
        calls.add(new ToolCall("read_skill_resource",Map.of("name","fixture-skill","path","reference.md")));
        String command=scenario.equals("nonzero")?(windows()?"exit /b 7":"exit 7"):"echo SCRIPT_EXECUTED > executed.txt";
        if(scenario.equals("missing"))command="paicli_missing_dependency_fixture_7f692";
        if(Set.of("long-error","timeout","cancel").contains(scenario)){
            Files.writeString(workspace.resolve("CommandFixture.java"),"""
                    import java.nio.file.*;
                    class CommandFixture {
                        public static void main(String[] args)throws Exception {
                            Files.writeString(Path.of("command.pid"),Long.toString(ProcessHandle.current().pid()));
                            if(args[0].equals("long-error")){System.err.print("Fixture error ".repeat(1000));System.exit(7);}
                            Thread.sleep(30000);
                        }
                    }
                    """);
            command="\""+Path.of(System.getProperty("java.home"),"bin",windows()?"java.exe":"java")+"\" CommandFixture.java "+scenario;
        }
        calls.add(new ToolCall("run_command",Map.of("command",command)));
        String goal=scenario.equals("unknown")?"Use $missing-skill":scenario.equals("explicit")?"Use $fixture-skill for the dedicated fixture":"Inspect the dedicated Skill fixture";
        var evidence=execute(workspace,List.of(source),goal,calls,scenario);
        if(scenario.equals("unknown")){
            assertEquals(0,evidence.providerCalls());assertFalse(Files.exists(workspace.resolve("executed.txt")));return;
        }
        assertEquals(1,evidence.approvals());
        assertTrue(evidence.observed().stream().anyMatch(s->s.contains("RESOURCE_ONLY_AFTER_READ")),evidence.toString());
        if(Set.of("deny","changed","nonzero","missing","long-error","timeout","cancel").contains(scenario))assertFalse(Files.exists(workspace.resolve("executed.txt")));
        else assertTrue(Files.readString(workspace.resolve("executed.txt")).contains("SCRIPT_EXECUTED"));
        if(Set.of("timeout","cancel").contains(scenario)){
            long pid=Long.parseLong(Files.readString(workspace.resolve("command.pid")));
            var process=ProcessHandle.of(pid);
            try{assertTrue(process.isEmpty()||!process.get().isAlive(),"command process must be gone");}
            finally{process.filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);}
        }
        try(var state=new StateStore(workspace)){
            var loads=state.skillLoads.findByStatus(SkillLoadStore.STATUS_LOADED).stream().filter(l->l.taskId().equals(evidence.taskId())).toList();
            assertEquals(1,loads.size());String run=loads.getFirst().runId();assertTrue(run.startsWith("run_"));
            var accesses=state.skillLoads.accessesForRun(run);
            assertTrue(accesses.stream().anyMatch(a->a.kind().equals("READ")&&a.resourcePath().equals("reference.md")&&a.occurrenceId().startsWith("occ_")));
            if(Set.of("nonzero","missing","long-error").contains(scenario))assertTrue(accesses.stream().anyMatch(a->a.status().equals("NON_ZERO")));
            if(scenario.equals("timeout"))assertTrue(accesses.stream().anyMatch(a->a.kind().equals("COMMAND")&&a.status().equals("UNKNOWN")),accesses.toString());
            if(Set.of("timeout","cancel").contains(scenario)){
                assertFalse(accesses.stream().anyMatch(a->a.kind().equals("COMMAND")&&a.status().equals("SUCCEEDED")));
                assertTrue(state.r1aDurable.hasStartedOrUnknownOccurrence(com.paicli.identity.CanonicalId.parse(evidence.taskId())));
            }
            if(Set.of("deny","changed").contains(scenario))assertTrue(accesses.stream().noneMatch(a->a.kind().equals("COMMAND")));
            assertTrue(state.skillLoads.reportForTaskRun(evidence.taskId(),run).stream().anyMatch(line->line.contains("fixture-skill READ reference.md")));
        }
    }

    static Evidence execute(Path workspace,List<Path> sources,String goal,List<ToolCall> calls,String scenario)throws Exception {
        var json=new ObjectMapper();var observed=new CopyOnWriteArrayList<String>();var providerCalls=new AtomicInteger();
        ModelProvider provider=(messages,definitions)->{
            int index=providerCalls.getAndIncrement();
            observed.addAll(messages.stream().map(AgentMessage::content).filter(Objects::nonNull).toList());
            if(index==0&&!goal.contains("$"))assertFalse(messages.stream().anyMatch(m->Objects.toString(m.content(),"").contains("BODY_ONLY_AFTER_ACTIVATION")));
            return index<calls.size()?ModelResponse.toolCallResponse("Dedicated Skill fixture",calls.get(index)):
                    ModelResponse.finalAnswer("Checks finished; any reported tool or command error remains a failed check. Consult the recorded evidence.");
        };
        Config config=Config.defaults().merge(json.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request","enable_plan":false,
                 "enable_verify":false,"enable_repair":false,"enable_skills":true,"enable_hooks":false,
                 "max_iterations":12,"context_window_tokens":131072,"command_timeout_seconds":COMMAND_TIMEOUT}
                """.replace("COMMAND_TIMEOUT",scenario.equals("timeout")?"4":"30"),Config.class));
        String task;int approvals=0;String terminalStatus;
        var events=new CopyOnWriteArrayList<com.paicli.event.AgentEvent>();
        try(var runtime=InteractiveRuntimeAdapter.open(workspace,new ProductionInteractiveTaskExecutor(workspace,()->config,ignored->provider),events::add)){
            var session=new InteractiveSession("skills",workspace,"ollama","fixture","on-request",InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();runtime.openSession(session);
            var registry=new SlashCommandRegistry();
            var router=new SlashCommandRouter("test",registry,new SlashCommandParser(registry),List::of).withSkillCommands(runtime::skillCommand);
            for(Path source:sources){
                String command="/skills import \""+source+"\"";
                var preview=router.route(command,session,null);assertEquals("SKILLS",preview.code(),preview.toString());
                String hash=preview.lines().stream().filter(line->line.startsWith("SHA-256: ")).findFirst().orElseThrow().substring(9);
                var imported=router.route(command+" "+hash,session,null);assertEquals("SKILLS",imported.code(),imported.toString());
                var name=SkillLoader.inspectPackage(source).name();
                assertEquals(SkillPackageFiles.snapshot(source).hash(),SkillPackageFiles.snapshot(workspace.resolve(".paicli/skills").resolve(name)).hash());
            }
            runtime.submit(new InteractiveTaskRequest("skills",workspace,goal,List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();task=foreground.taskId();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);Set<String> handled=new HashSet<>();boolean cancelled=false;
            while(!foreground.terminal().isDone()&&System.nanoTime()<deadline){
                var pending=runtime.pendingApproval();
                if(pending.isPresent()&&handled.add(pending.get().requestId())){
                    assertEquals("SKILL_COMMAND_REJECTED",runtime.skillCommand(List.of("init","during-task"),session).code());
                    if(scenario.equals("changed"))Files.writeString(workspace.resolve(".paicli/skills/fixture-skill/reference.md"),"changed while approval pending");
                    assertTrue(runtime.respondApproval(pending.get().requestId(),scenario.equals("deny")?"n":"y").resolved());approvals++;
                }
                if(scenario.equals("cancel")&&!cancelled&&Files.exists(workspace.resolve("command.pid"))){runtime.requestCancellation();cancelled=true;}
                Thread.sleep(10);
            }
            var terminal=foreground.terminal().get(1,TimeUnit.SECONDS);terminalStatus=terminal.status().name();
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(5)));
            if(!Set.of("unknown","deny","changed","timeout","cancel").contains(scenario))assertEquals(TaskSubmissionResult.Status.COMPLETED,terminal.status(),terminal+" events="+events);
        }
        return new Evidence(task,providerCalls.get(),approvals,List.copyOf(observed),terminalStatus);
    }
    static boolean windows(){return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");}
    record Evidence(String taskId,int providerCalls,int approvals,List<String> observed,String terminalStatus) { }
}
