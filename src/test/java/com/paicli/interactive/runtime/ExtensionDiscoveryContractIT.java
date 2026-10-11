package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.config.*;
import com.paicli.mcp.tool.McpToolNames;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import com.paicli.skill.SkillPackageFiles;
import com.paicli.state.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted Provider contract: proves visibility, selection plumbing and non-use are supported.
 * It does NOT measure a real model's ability to choose an extension from an ordinary goal. */
class ExtensionDiscoveryContractIT {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings={"skill", "mcp", "combined", "neither"})
    void ordinaryGoalExposesCatalogAndToolsWithoutEagerBodies(String scenario) throws Exception {
        boolean useSkill=Set.of("skill","combined").contains(scenario);
        boolean useMcp=Set.of("mcp","combined").contains(scenario);
        var json=new ObjectMapper();
        Path relevant=packageAt("z-debug", "Diagnose incorrect Java arithmetic", "RELEVANT_BODY");
        Path distractor=packageAt("a-layout", "Review CSS layout", "UNRELATED_BODY");
        Files.writeString(relevant.resolve("reference.md"),"FIRST_PAGE_"+"a".repeat(53)+"SECOND_PAGE_"+"b".repeat(52));
        Files.writeString(relevant.resolve("unused.md"),"UNREQUESTED_RESOURCE");
        Path mcpConfig=workspace.resolve("connection.json");
        json.writeValue(mcpConfig.toFile(),new McpClientConfig(List.of(
                R3TestSupport.config(workspace,"SINGLE_PAGE",List.of("echo"),McpRuntimeLimits.defaults()))));
        String externalTool=McpToolNames.alias("fake","echo");
        var steps=new ArrayList<ToolCall>();
        if(useSkill){
            steps.add(new ToolCall("load_skill",Map.of("name","z-debug")));
            steps.add(new ToolCall("read_skill_resource",Map.of("name","z-debug","path","reference.md","offset",0,"max_chars",64)));
            steps.add(new ToolCall("read_skill_resource",Map.of("name","z-debug","path","reference.md","offset",64,"max_chars",64)));
        }
        if(useMcp)steps.add(new ToolCall(externalTool,Map.of("value","invoice-2047")));
        var turns=new AtomicInteger();
        ModelProvider provider=(messages,definitions)->{
            int turn=turns.getAndIncrement();
            String visible=messages.stream().map(AgentMessage::content).filter(Objects::nonNull)
                    .reduce("",(a,b)->a+"\n"+b);
            var names=definitions.stream().map(ToolDefinition::name).toList();
            assertTrue(names.containsAll(List.of("read_file","run_command","load_skill","read_skill_resource",externalTool)));
            for(String name:List.of("load_skill","read_skill_resource",externalTool)){
                String description=definitions.stream().filter(d->d.name().equals(name))
                        .findFirst().orElseThrow().description();
                assertTrue(description.contains("submit this tool alone"));
                assertTrue(description.contains("none of its calls ran"));
            }
            assertTrue(visible.contains("Diagnose incorrect Java arithmetic"));
            assertTrue(visible.contains("Review CSS layout"));
            assertFalse(visible.contains("UNRELATED_BODY"));
            assertFalse(visible.contains("UNREQUESTED_RESOURCE"));
            assertEquals(useSkill&&turn>=1,visible.contains("RELEVANT_BODY"));
            assertEquals(useSkill&&turn>=2,visible.contains("FIRST_PAGE_"));
            assertEquals(useSkill&&turn>=3,visible.contains("SECOND_PAGE_"));
            // Inspect the definition actually delivered through the production AgentLoop.
            String commandDescription=definitions.stream().filter(d->d.name().equals("run_command"))
                    .findFirst().orElseThrow().description();
            assertTrue(commandDescription.contains(System.getProperty("os.name").toLowerCase(Locale.ROOT)
                    .contains("windows")?"cmd.exe":"/bin/sh"));
            return turn<steps.size()?ModelResponse.toolCallResponse("Contract fixture",steps.get(turn))
                    :ModelResponse.finalAnswer("The requested inspection is complete.");
        };
        Config config=Config.defaults().merge(json.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":true,"enable_hooks":false,"max_iterations":8,"context_window_tokens":131072}
                """,Config.class));
        String task; int approvals=0;
        try(var runtime=InteractiveRuntimeAdapter.open(workspace,
                new ProductionInteractiveTaskExecutor(workspace,()->config,ignored->provider),event->{})){
            var session=new InteractiveSession("discovery",workspace,"ollama","fixture","on-request",
                    InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();runtime.openSession(session);
            for(Path source:List.of(distractor,relevant)){
                assertEquals("SKILLS",runtime.skillCommand(List.of("import",source.toString(),
                        SkillPackageFiles.snapshot(source).hash()),session).code());
            }
            assertEquals("MCP",runtime.mcpCommand(List.of("config","import",mcpConfig.toString()),session).code());
            var preview=runtime.mcpCommand(List.of("connect","fake"),session);
            String confirmation=preview.lines().getLast().substring(preview.lines().getLast().lastIndexOf(' ')+1);
            assertEquals("MCP",runtime.mcpCommand(List.of("connect","fake",confirmation),session).code());
            String goal=switch(scenario){
                case "skill" -> "Explain why the Java discount calculation returns an incorrect amount.";
                case "mcp" -> "Check whether the remote service returns invoice-2047 unchanged.";
                case "combined" -> "Investigate the incorrect discount and check the remote service response for invoice-2047.";
                default -> "Reply with a short acknowledgement of this message.";
            };
            runtime.submit(new InteractiveTaskRequest("discovery",workspace,goal,List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();task=foreground.taskId();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);var answered=new HashSet<String>();
            while(!foreground.terminal().isDone()&&System.nanoTime()<deadline){
                var pending=runtime.pendingApproval();
                if(pending.isPresent()&&answered.add(pending.get().requestId())){
                    assertTrue(runtime.respondApproval(pending.get().requestId(),"y").resolved());approvals++;
                }
                Thread.sleep(10);
            }
            assertEquals(TaskSubmissionResult.Status.COMPLETED,foreground.terminal().get(1,TimeUnit.SECONDS).status());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(3)));
        }
        assertEquals(steps.size()+1,turns.get());assertEquals(useMcp?1:0,approvals);
        try(var state=new StateStore(workspace)){
            var loads=state.skillLoads.findByStatus(SkillLoadStore.STATUS_LOADED).stream()
                    .filter(l->l.taskId().equals(task)).toList();
            assertEquals(2,loads.size());String run=loads.getFirst().runId();
            var accesses=state.skillLoads.accessesForRun(run);
            assertEquals(useSkill?4:0,accesses.size());
            if(useSkill){
                var activation=accesses.stream().filter(a->a.kind().equals("ACTIVATE")).findFirst().orElseThrow();
                assertEquals("MODEL",activation.status());assertNotNull(activation.toolCallId());
                assertTrue(activation.occurrenceId().startsWith("occ_"));
                assertEquals(List.of("z-debug"),state.skillSelections.findSelectedByRun(run).stream()
                        .map(SkillSelectionStore.SelectedSkillRecord::skillName).toList());
                assertEquals(List.of(0,64),accesses.stream().filter(a->a.resourcePath().equals("reference.md"))
                        .map(SkillLoadStore.Access::offset).sorted().toList());
            }
        }
    }

    private Path packageAt(String name,String description,String body)throws Exception{
        Path source=Files.createDirectories(workspace.resolve("download").resolve(name));
        Files.writeString(source.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: "+description+"\n---\n"+body);
        return source;
    }
}
