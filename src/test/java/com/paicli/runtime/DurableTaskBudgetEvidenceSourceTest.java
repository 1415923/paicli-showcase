package com.paicli.runtime;

import com.paicli.config.Config;
import com.paicli.agent.AgentLoop;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.ModelResponse;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.state.ProviderCallTelemetryStore;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DurableTaskBudgetEvidenceSourceTest {
    @TempDir Path workspace;

    @Test void freshTaskRunWithNoProviderAttemptIsCompleteZeroUsage() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    Config.defaults(),RuntimeManifest.disabled(),Instant.now());

            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),root.initialRun().id().value());

            assertTrue(evidence.complete(),evidence.missingEvidence().toString());
            assertEquals(0,evidence.providerTurns());
            assertEquals(0,evidence.inputTokens());
            assertEquals(0,evidence.outputTokens());
        }
    }

    @Test void startedProviderAttemptWithoutTerminalUsageFailsClosed() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    Config.defaults(),RuntimeManifest.disabled(),Instant.now());
            DurableRun run=root.initialRun();
            assertTrue(state.providerTelemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(
                    "12345678-1234-4234-8234-123456789011","mock","model",
                    ProviderCallTelemetryStore.OperationKind.PLAN,
                    new ProviderCallContext(null,task.value(),run.id().value(),null),Instant.now())));

            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),run.id().value());

            assertFalse(evidence.complete());
            assertTrue(evidence.missingEvidence().contains("PROVIDER_LEDGER_INCOMPLETE"));
        }
    }

    @Test void oneCompletedProviderCallUsesAuthoritativeDurableUsage() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    Config.defaults(),RuntimeManifest.disabled(),Instant.now());
            record(state,task.value(),root.initialRun().id().value(),
                    "12345678-1234-4234-8234-123456789012",41,9);

            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),root.initialRun().id().value());

            assertTrue(evidence.complete(),evidence.missingEvidence().toString());
            assertEquals(1,evidence.providerTurns());
            assertEquals(41,evidence.inputTokens());
            assertEquals(9,evidence.outputTokens());
        }
    }

    @Test void mixedCompletedAndOpenTelemetryFailsClosedWithoutResettingUsage() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    Config.defaults(),RuntimeManifest.disabled(),Instant.now());
            DurableRun run=root.initialRun();
            record(state,task.value(),run.id().value(),
                    "12345678-1234-4234-8234-123456789013",17,3);
            assertTrue(state.providerTelemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(
                    "12345678-1234-4234-8234-123456789014","mock","model",
                    ProviderCallTelemetryStore.OperationKind.CHAT,
                    new ProviderCallContext(null,task.value(),run.id().value(),null),Instant.now())));

            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),run.id().value());

            assertFalse(evidence.complete());
            assertEquals(2,evidence.providerTurns());
            assertEquals(17,evidence.inputTokens());
            assertEquals(3,evidence.outputTokens());
            assertTrue(evidence.missingEvidence().contains("PROVIDER_LEDGER_INCOMPLETE"));
        }
    }

    @Test void aggregatesProviderUsageAcrossInitialAndResumeRunsWithoutReset() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    Config.defaults(),RuntimeManifest.disabled(),Instant.now());
            DurableRun initial=root.initialRun();
            record(state,task.value(),initial.id().value(),"12345678-1234-4234-8234-123456789001",10,5);

            DurableRun resume=new DurableRun(CanonicalId.newId(EntityFamily.RUN),task,
                    initial.threadId(),"RESUME",initial.id(),initial.manifestId(),
                    initial.manifestHash(),Instant.now(),null);
            assertEquals(com.paicli.state.R1aStoreResults.CreateResult.SUCCESS,
                    state.r1aDurable.createRun(resume));
            record(state,task.value(),resume.id().value(),"12345678-1234-4234-8234-123456789002",20,7);

            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),resume.id().value());
            assertTrue(evidence.complete(),evidence.missingEvidence().toString());
            assertEquals(2,evidence.providerTurns());
            assertEquals(30,evidence.inputTokens());
            assertEquals(12,evidence.outputTokens());
            assertEquals(0,evidence.requestedToolCalls());
            assertEquals(0,evidence.admittedToolCalls());
            assertEquals(0,evidence.toolCalls());
        }
    }

    @Test void validRunOwnedByAnotherTaskMakesUsageIncompleteWithoutHidingTotals()
            throws Exception {
        try(StateStore state=new StateStore(workspace)){
            var runtime=new InstructionManifestRuntime(state,workspace);
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=runtime.admitRoot(task,"goal",10_000,Config.defaults(),
                    RuntimeManifest.disabled(),Instant.now());
            CanonicalId otherTask=CanonicalId.newId(EntityFamily.TASK);
            var otherRoot=runtime.admitRoot(otherTask,"other goal",10_000,Config.defaults(),
                    RuntimeManifest.disabled(),Instant.now());
            record(state,task.value(),root.initialRun().id().value(),
                    "12345678-1234-4234-8234-123456789021",11,2);
            record(state,task.value(),otherRoot.initialRun().id().value(),
                    "12345678-1234-4234-8234-123456789022",29,7);

            var usage=state.providerTelemetry.taskUsage(task.value());
            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),root.initialRun().id().value());

            assertFalse(usage.complete());
            assertEquals(2,usage.providerTurns());
            assertEquals(40,usage.inputTokens());
            assertEquals(9,usage.outputTokens());
            assertFalse(evidence.complete());
            assertEquals(2,evidence.providerTurns());
            assertEquals(40,evidence.inputTokens());
            assertEquals(9,evidence.outputTokens());
            assertTrue(evidence.missingEvidence().contains("PROVIDER_LEDGER_INCOMPLETE"));
        }
    }

    @Test void nonexistentNonNullRunMakesUsageIncomplete() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    Config.defaults(),RuntimeManifest.disabled(),Instant.now());
            record(state,task.value(),CanonicalId.newId(EntityFamily.RUN).value(),
                    "12345678-1234-4234-8234-123456789023",13,4);

            var usage=state.providerTelemetry.taskUsage(task.value());
            var evidence=new DurableTaskBudgetEvidenceSource(state)
                    .snapshot(task.value(),root.initialRun().id().value());

            assertFalse(usage.complete());
            assertEquals(1,usage.providerTurns());
            assertEquals(13,usage.inputTokens());
            assertEquals(4,usage.outputTokens());
            assertFalse(evidence.complete());
            assertTrue(evidence.missingEvidence().contains("PROVIDER_LEDGER_INCOMPLETE"));
        }
    }

    @Test void foreignRunEvidenceBlocksProviderBeforeInvocation() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            var runtime=new InstructionManifestRuntime(state,workspace);
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=runtime.admitRoot(task,"goal",10_000,Config.defaults(),
                    RuntimeManifest.disabled(),Instant.now());
            CanonicalId otherTask=CanonicalId.newId(EntityFamily.TASK);
            var otherRoot=runtime.admitRoot(otherTask,"other goal",10_000,Config.defaults(),
                    RuntimeManifest.disabled(),Instant.now());
            record(state,task.value(),otherRoot.initialRun().id().value(),
                    "12345678-1234-4234-8234-123456789024",5,1);
            var source=new DurableTaskBudgetEvidenceSource(state);
            AtomicInteger providerCalls=new AtomicInteger();
            var provider=(com.paicli.provider.ModelProvider)(messages,tools)->{
                providerCalls.incrementAndGet();
                return ModelResponse.finalAnswer("must not run");
            };
            AgentLoop loop=new AgentLoop(provider,new ToolRegistry(),1)
                    .bindTaskProviderBudgetGate((ignoredTask,ignoredRun)->{
                        var evidence=source.snapshot(
                                task.value(),root.initialRun().id().value());
                        return evidence.complete()?TaskProviderBudgetGate.Admission.allowed()
                                :TaskProviderBudgetGate.Admission.rejected(
                                        "TASK_BUDGET_EVIDENCE_INCOMPLETE");
                    });

            var rejected=assertThrows(TaskProviderBudgetGate.AdmissionException.class,
                    ()->loop.run("goal"));

            assertEquals("TASK_BUDGET_EVIDENCE_INCOMPLETE",rejected.code());
            assertEquals(0,providerCalls.get());
        }
    }

    private static void record(StateStore state,String task,String run,String correlation,
            int input,int output){
        Instant now=Instant.now();
        assertTrue(state.providerTelemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(
                correlation,"mock","model",ProviderCallTelemetryStore.OperationKind.CHAT,
                new ProviderCallContext(null,task,run,null),now)));
        var observation=new ProviderCallObservation(input,output,input+output,0,0,0,
                ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE,0,null,null,null,null,false);
        assertTrue(state.providerTelemetry.finalizeCall(new ProviderCallTelemetryStore.CallFinish(
                correlation,ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,Instant.now(),1,
                "FINAL",0,observation,"a".repeat(64),"test")));
    }
}
