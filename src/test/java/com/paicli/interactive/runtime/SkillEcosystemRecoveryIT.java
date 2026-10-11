package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.identity.*;
import com.paicli.interactive.session.*;
import com.paicli.model.*;
import com.paicli.runtime.*;
import com.paicli.state.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SkillEcosystemRecoveryIT {
    @TempDir Path workspace;
    @ParameterizedTest @ValueSource(strings={"unchanged","body","resource","added"})
    void formalRecoveryChecksEntirePackageBeforeProviderOrResumeAdmission(String scenario)throws Exception {
        Path skill=Files.createDirectories(workspace.resolve("skills/frozen"));
        Files.writeString(skill.resolve("SKILL.md"),"---\nname: frozen\ndescription: Frozen recovery fixture\n---\nOriginal instructions\n");
        Files.writeString(skill.resolve("resource.md"),"Original resource");
        Config config=Config.defaults().merge(new ObjectMapper().readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"read-only","enable_plan":false,
                 "enable_verify":false,"enable_repair":false,"enable_skills":true,"enable_hooks":false,"context_window_tokens":131072}
                """,Config.class));
        StateStore state=new StateStore(workspace);Instant at=Instant.now().minusSeconds(60);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"Continue $frozen",10000,config,new RuntimeManifestHasher(workspace).compute(config),at);
        state.checkpoints.save(task.value(),0,"RUNNING",List.of(AgentMessage.userGoal("Continue $frozen")));
        state.events.append(task.value(),0,AgentMessage.userGoal("Continue $frozen"));
        if(scenario.equals("body"))Files.writeString(skill.resolve("SKILL.md"),"changed",StandardOpenOption.APPEND);
        if(scenario.equals("resource"))Files.writeString(skill.resolve("resource.md"),"Changed resource");
        if(scenario.equals("added"))Files.writeString(skill.resolve("new.md"),"New resource");
        var providerCalls=new AtomicInteger();
        var executor=new ProductionInteractiveTaskExecutor(workspace,()->config,ignored->(messages,tools)->{
            providerCalls.incrementAndGet();return ModelResponse.finalAnswer("Recovered frozen package.");
        });
        try(var runtime=new InteractiveRuntimeAdapter(workspace,executor,ignored->{},new InteractiveRuntimeView(),state)){
            var session=new InteractiveSession("skill-recovery",workspace,"ollama","fixture","read-only",InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();runtime.openSession(session);
            new SavedSessionService(state,workspace,Clock.systemUTC()).bind(session,root.threadId().value());
            assertEquals("PREPARING",runtime.recoverTask(task.value()).code());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(10)));
            if(scenario.equals("unchanged")){
                assertEquals(1,providerCalls.get(),runtime.lastResult().toString());
                assertEquals(2,state.r1aDurable.findRunsByTask(task).size());
            }else {
                assertEquals(0,providerCalls.get());
                assertEquals("TASK_RECOVERY_RUNTIME_DRIFT",runtime.lastResult().orElseThrow().code(),runtime.lastResult().toString());
                assertEquals(1,state.r1aDurable.findRunsByTask(task).size());
            }
        }
    }
}
