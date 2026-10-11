package com.paicli.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.provider.ModelProvider;
import com.paicli.state.StateStore;
import com.paicli.state.DatabaseManager;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class R1bRootRuntimeIntegrationTest {
    @TempDir Path workspace;

    @Test
    void initialRunInjectsCommittedManifestBeforeProviderRequest() throws Exception {
        Files.writeString(workspace.resolve("PAICLI.md"), "frozen-paicli-rule");
        Files.writeString(workspace.resolve("AGENTS.md"), "frozen-agents-rule");
        try (StateStore state = new StateStore(workspace)) {
            RecordingProvider provider = new RecordingProvider();
            Config config = config();
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            ModelProvider execution=new ContextualModelProvider(provider,state,workspace,
                    task.value(),"mock","model-a",OperationKind.CHAT,
                    new CapabilityProfile("test",30_000,
                            LimitProvenance.CONFIGURED_LIMIT,256,64),false);
            AtomicReference<FencedExecutionGuard> boundAuthority=new AtomicReference<>();
            CodingTaskObserver observer=new CodingTaskObserver(){
                @Override public void executionAuthorityBound(FencedExecutionGuard guard){
                    boundAuthority.set(guard);
                }
            };
            CodingTaskRunner runner = new CodingTaskRunner(
                    state, new PlanService(provider, state.plans),
                    new VerificationService(workspace, state.verifications,
                            new PersistenceSanitizer(), 120, 50_000),
                    agentLoopFactory(state, execution, config),
                    config, new PersistenceSanitizer(), null, null,
                    RuntimeManifest.disabled(), observer,
                    (taskId, runId) -> false,
                    new InstructionManifestRuntime(state, workspace));

            CodingTaskResult result = runner.run(task.value(), "implement safely");

            assertEquals(com.paicli.state.TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertSame(result.executionGuard(),boundAuthority.get());
            assertTrue(provider.messages.stream()
                    .map(AgentMessage::content)
                    .anyMatch(content -> content.contains("frozen-paicli-rule")
                            && content.contains("frozen-agents-rule")));
            var manifest = state.r1aDurable.findManifestByTask(task).orElseThrow();
            var durableRun = state.r1aDurable.findRun(
                    CanonicalId.parse(result.runId())).orElseThrow();
            assertEquals(manifest.id(), durableRun.manifestId());
        }
    }

    @Test
    void lateRootPersistenceFailureRollsBackBeforeAnyProviderRequest()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore ignored=new StateStore(workspace)){ }
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement()){
            statement.execute("CREATE TRIGGER root_runtime_fail AFTER INSERT ON thread_heads "
                    +"BEGIN SELECT RAISE(ABORT,'late root failure'); END");
        }
        RecordingProvider provider=new RecordingProvider();
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            Config config=config();
            CodingTaskRunner runner=new CodingTaskRunner(state,
                    new PlanService(provider,state.plans),
                    new VerificationService(workspace,state.verifications,
                            new PersistenceSanitizer(),120,50_000),
                    agentLoopFactory(state,provider,config),config,
                    new PersistenceSanitizer(),null,null,RuntimeManifest.disabled(),
                    CodingTaskObserver.NOOP,(taskId,runId)->false,
                    new InstructionManifestRuntime(state,workspace));

            assertThrows(InstructionManifestRuntime.AdmissionException.class,
                    ()->runner.run(task.value(),"must rollback"));
            assertEquals(0,provider.calls);
            assertTrue(state.r1aDurable.findTask(task).isEmpty());
            assertTrue(state.r1aDurable.findManifestByTask(task).isEmpty());
        }
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement()){
            for(String table:List.of("work_threads","task_identities",
                    "protected_instruction_artifacts","instruction_manifests",
                    "instruction_entries","durable_runs","task_context_generations",
                    "task_context_pointers","thread_heads")){
                try(var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)){
                    assertTrue(result.next());assertEquals(0,result.getInt(1),table);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"runtime-task-after","coding-run-after","runtime-state-after"})
    void productionRootEntryRollsBackRuntimeAndV19Authority(String stage)throws Exception{
        Path root=workspace.resolve(stage);Files.createDirectories(root);
        Files.writeString(root.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore ignored=new StateStore(root)){ }
        String trigger=switch(stage){
            case "runtime-task-after" -> "CREATE TRIGGER root_runtime_fail AFTER INSERT ON tasks BEGIN SELECT RAISE(ABORT,'root runtime failure'); END";
            case "coding-run-after" -> "CREATE TRIGGER root_runtime_fail AFTER INSERT ON coding_runs BEGIN SELECT RAISE(ABORT,'root runtime failure'); END";
            default -> "CREATE TRIGGER root_runtime_fail AFTER UPDATE OF status ON tasks WHEN NEW.status='RUNNING' BEGIN SELECT RAISE(ABORT,'root runtime failure'); END";
        };
        try(DatabaseManager manager=new DatabaseManager(root.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement()){
            statement.execute(trigger);
        }
        RecordingProvider provider=new RecordingProvider();CanonicalId task=CanonicalId.newId(
                EntityFamily.TASK);
        try(StateStore state=new StateStore(root)){
            Config config=config(root);
            CodingTaskRunner runner=new CodingTaskRunner(state,new PlanService(provider,state.plans),
                    new VerificationService(root,state.verifications,new PersistenceSanitizer(),
                            120,50_000),
                    agentLoopFactory(state,provider,config),config,
                    new PersistenceSanitizer(),null,null,RuntimeManifest.disabled(),
                    CodingTaskObserver.NOOP,(taskId,runId)->false,
                    new InstructionManifestRuntime(state,root));
            assertThrows(InstructionManifestRuntime.AdmissionException.class,
                    ()->runner.run(task.value(),"must rollback all authority"));
            assertEquals(0,provider.calls);
            assertTrue(state.tasks.findById(task.value()).isEmpty());
            assertTrue(state.codingRuns.findByTask(task.value()).isEmpty());
            assertTrue(state.r1aDurable.findTask(task).isEmpty());
        }
        try(DatabaseManager manager=new DatabaseManager(root.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement()){
            for(String table:List.of("work_threads","task_identities",
                    "protected_instruction_artifacts","instruction_manifests",
                    "instruction_entries","durable_runs","task_context_generations",
                    "task_context_pointers","thread_heads","tasks","coding_runs",
                    "events","checkpoints","task_plans","verification_runs",
                    "repair_attempts","hook_runs")){
                try(var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)){
                    assertTrue(result.next());assertEquals(0,result.getInt(1),table);
                }
            }
        }
    }

    private Config config() {
        return config(workspace);
    }

    private Config config(Path root) {
        return new Config(root.toString(), 30, 50_000, 200_000,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "model-a",
                "", "", "system", 3, "", 120, 50_000, 1,
                false, false, false);
    }

    private static CodingTaskRunner.AgentLoopFactory agentLoopFactory(
            StateStore state, ModelProvider provider, Config config) {
        return taskId -> new AgentLoop(provider, new ToolRegistry(),
                config.effectiveMaxIterations(), null, null, state, taskId,
                config.effectiveProvider(), config.model());
    }

    private static final class RecordingProvider implements ModelProvider {
        private List<AgentMessage> messages = List.of();
        private int calls;

        @Override
        public ModelResponse chat(List<AgentMessage> messages,
                List<com.paicli.model.ToolDefinition> tools) {
            calls++;
            this.messages = List.copyOf(messages);
            return ModelResponse.finalAnswer("done");
        }
    }
}
