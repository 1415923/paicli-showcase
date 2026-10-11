package com.paicli.state;

import com.paicli.agent.SharedToolBatchPolicy;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.CompileRequest;
import com.paicli.context.ContextModels.ContextInvocation;
import com.paicli.context.ContextModels.Identity;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextModels.SourceReference;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.mcp.config.*;
import com.paicli.mcp.manifest.*;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.FollowUpService;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.SteeringClassifier;
import com.paicli.runtime.TaskExecutionAuthority;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.FollowUpModels.ConfirmationDecision;
import com.paicli.state.FollowUpModels.ConfirmationKind;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.SteeringModels.Provenance;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class R3ContextSafePointAuthorityTest {
    @TempDir Path workspace;
    @Test void authoritativeOccurrenceBlocksWithoutAnyTelemetryAndApprovalAlsoBlocks()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"safe-point"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            assertTrue(state.contexts.safePoint(pointer,authority(admitted)).safe());
            var occurrence=admitted.guard().admitOperationOccurrence("7","provider-safe-point-test",
                    "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input"));
            assertEquals(0,scalar("SELECT count(*) FROM provider_call_telemetry"));
            assertEquals("CONTEXT_EXTERNAL_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            admitted.guard().failOccurrence(occurrence,"telemetry unavailable but authority closed");
            assertTrue(state.contexts.safePoint(pointer,authority(admitted)).safe());
            admitted.guard().recordApproval(8,"call-pending","run_command","HIGH",
                    "NEEDS_APPROVAL","pending user approval");
            assertEquals("CONTEXT_APPROVAL_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            assertEquals(base.contextGenerationId(),pointer.contextGenerationId());
        }
    }

    @Test void correctCallerAuthorityIsAccepted()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"correct-owner"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            assertTrue(state.contexts.safePoint(pointer,authority(admitted)).safe());
        }
    }

    @Test void ownerMismatchWithOtherwiseCurrentAuthorityIsRejected()throws Exception{
        assertCallerMutationRejected("owner-mismatch",current->new ExecutionAuthoritySnapshot(
                current.taskId(),current.runId(),"other-owner",current.leaseToken(),
                current.fencingToken(),current.casVersion()));
    }

    @Test void leaseTokenMismatchWithOtherwiseCurrentAuthorityIsRejected()throws Exception{
        assertCallerMutationRejected("token-mismatch",current->new ExecutionAuthoritySnapshot(
                current.taskId(),current.runId(),current.ownerInstanceId(),"wrong-lease-token",
                current.fencingToken(),current.casVersion()));
    }

    @Test void fencingMismatchWithOtherwiseCurrentAuthorityIsRejected()throws Exception{
        assertCallerMutationRejected("fence-mismatch",current->new ExecutionAuthoritySnapshot(
                current.taskId(),current.runId(),current.ownerInstanceId(),current.leaseToken(),
                current.fencingToken()+1,current.casVersion()));
    }

    @Test void wrongRunWithOtherwiseCurrentAuthorityIsRejected()throws Exception{
        assertCallerMutationRejected("run-mismatch",current->new ExecutionAuthoritySnapshot(
                current.taskId(),CanonicalId.newId(EntityFamily.RUN),current.ownerInstanceId(),
                current.leaseToken(),current.fencingToken(),current.casVersion()));
    }

    @Test void wrongTaskWithOtherwiseCurrentAuthorityIsRejected()throws Exception{
        assertCallerMutationRejected("task-mismatch",current->new ExecutionAuthoritySnapshot(
                CanonicalId.newId(EntityFamily.TASK),current.runId(),current.ownerInstanceId(),
                current.leaseToken(),current.fencingToken(),current.casVersion()));
    }

    @Test void heartbeatMakesOldCasStaleButKeepsContextEpochAndFreshAuthorityValid()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"heartbeat"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            ExecutionAuthoritySnapshot stale=authority(admitted);
            admitted.guard().heartbeat("R3_COMPACTION_TEST");
            ExecutionAuthoritySnapshot refreshed=authority(admitted);
            assertEquals(stale.ownerInstanceId(),refreshed.ownerInstanceId());
            assertEquals(stale.leaseToken(),refreshed.leaseToken());
            assertEquals(stale.runId(),refreshed.runId());
            assertEquals(stale.fencingToken(),refreshed.fencingToken());
            assertEquals(stale.casVersion()+1,refreshed.casVersion());
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(pointer,stale).code());
            assertTrue(state.contexts.safePoint(pointer,refreshed).safe());
        }
    }

    @Test void expiredLeaseRejectsOtherwiseMatchingCaller()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"expired"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            try(var database=new DatabaseManager(workspace.resolve(".paicli/state"));
                var update=database.getConnection().prepareStatement(
                        "UPDATE task_ownership_leases SET expires_at=? WHERE task_id=?")){
                update.setString(1,Instant.now().minusSeconds(1).toString());
                update.setString(2,admitted.root().taskId().value());
                assertEquals(1,update.executeUpdate());
            }
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }

    @Test void formalOwnershipTransferAdvancesFenceAndRejectsOldAuthorityAndContext()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"before-transfer"));
            var oldPointer=state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.CHAT).orElseThrow();
            ExecutionAuthoritySnapshot old=authority(admitted);
            var lease=state.r1aDurable.readOwnership(root.taskId()).orElseThrow();
            var generationPointer=state.r1aDurable.readContextPointer(root.taskId()).orElseThrow();
            Instant takeoverAt=lease.expiresAt().plusSeconds(1);
            assertEquals(R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.takeoverOwnershipAndContextPointer(root.taskId(),
                            root.initialRun().id(),"r3-transfer-owner","r3-transfer-lease",
                            lease.casVersion(),generationPointer.generationId(),
                            generationPointer.ownerRunId(),generationPointer.version(),takeoverAt,
                            takeoverAt.plus(Duration.ofHours(1)),Duration.ZERO));
            var transferredLease=state.r1aDurable.readOwnership(root.taskId()).orElseThrow();
            assertEquals(lease.fencingToken()+1,transferredLease.fencingToken());
            assertEquals(lease.casVersion()+1,transferredLease.casVersion());
            var transferredGuard=new FencedExecutionGuard(state,
                    TaskExecutionAuthority.initial(transferredLease),Duration.ofHours(1),Instant::now);
            ExecutionAuthoritySnapshot authorityCurrent=transferredGuard.current().snapshot();
            ExecutionAuthoritySnapshot staleFence=new ExecutionAuthoritySnapshot(authorityCurrent.taskId(),
                    authorityCurrent.runId(),authorityCurrent.ownerInstanceId(),authorityCurrent.leaseToken(),
                    authorityCurrent.fencingToken()-1,authorityCurrent.casVersion());
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(oldPointer,staleFence).code());
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(oldPointer,old).code());
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(oldPointer,transferredGuard.current().snapshot()).code());
            var transferred=new R3ContextTestFixtures.Admitted(root,transferredGuard);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,transferred,OperationKind.CHAT,8,"after-transfer"));
            var current=state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.CHAT).orElseThrow();
            assertTrue(state.contexts.safePoint(current,transferredGuard.current().snapshot()).safe());
        }
    }
    @Test void authoritativeToolEffectStartedAndUnknownBothReject()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"tool-effect"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            var occurrence=admitted.guard().admitOperationOccurrence("11","tool-effect-safe-point",
                    "TOOL","run_command",FencedExecutionGuard.canonicalInputHash("bounded-input"));
            assertEquals("CONTEXT_EXTERNAL_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            admitted.guard().unknownOccurrence(occurrence,"external effect outcome unknown");
            assertEquals("CONTEXT_EXTERNAL_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }
    @Test void needsIntentConfirmationFollowUpRejectsCompaction()throws Exception{
        assertFollowUpStateRejects("NEEDS_INTENT_CONFIRMATION");
    }

    @Test void needsRiskConfirmationFollowUpRejectsCompaction()throws Exception{
        assertFollowUpStateRejects("NEEDS_RISK_CONFIRMATION");
    }

    private void assertFollowUpStateRejects(String followUpState)throws Exception{
        Path rootPath=workspace.resolve("followup-"+followUpState.toLowerCase());
        try(StateStore state=new StateStore(rootPath)){
            var admitted=R3ContextTestFixtures.admit(state,rootPath);var root=admitted.root();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    rootPath,admitted,OperationKind.CHAT,8,followUpState));
            var pointer=state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.CHAT).orElseThrow();
            try(var database=new DatabaseManager(rootPath.resolve(".paicli/state"));
                var terminalTask=database.getConnection().prepareStatement("""
                  UPDATE task_identities SET status='COMPLETED',terminal_at=? WHERE task_id=?
                  """);
                var terminalRun=database.getConnection().prepareStatement("""
                  UPDATE durable_runs SET terminal_status='COMPLETED' WHERE task_id=? AND run_id=?
                  """)){
                terminalTask.setString(1,Instant.now().toString());
                terminalTask.setString(2,root.taskId().value());
                assertEquals(1,terminalTask.executeUpdate());
                terminalRun.setString(1,root.taskId().value());
                terminalRun.setString(2,root.initialRun().id().value());
                assertEquals(1,terminalRun.executeUpdate());
            }
            Config config=new Config(rootPath.toString(),30,50_000,200_000,Config.MODE_DENY,
                    10_000,Config.PROVIDER_MOCK,"model",null,null,"system",3,"",120,
                    50_000,1,false,false,false);
            FollowUpService service=new FollowUpService(state,
                    new InstructionManifestRuntime(state,rootPath),config,
                    RuntimeManifest.disabled(),10_000);
            var pending=service.begin(root.threadId(),"please inspect this",false,"owner",
                    "session",Instant.now());
            if("NEEDS_RISK_CONFIRMATION".equals(followUpState)){
                Files.writeString(rootPath.resolve("AGENTS.md"),"changed authority\n");
                pending=service.confirm(pending.candidate().capsuleId(),ConfirmationKind.INTENT,
                        pending.candidate().version(),"owner","session",
                        ConfirmationDecision.CONTINUE_CURRENT_THREAD,Instant.now());
            }
            assertEquals(followUpState,pending.candidate().state().name());
            assertEquals("CONTEXT_CONFIRMATION_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }
    @Test void verificationAndRepairRunningEachRejectCompaction()throws Exception{
        Path verificationRoot=workspace.resolve("verification");
        try(StateStore state=new StateStore(verificationRoot)){
            var admitted=R3ContextTestFixtures.admit(state,verificationRoot);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    verificationRoot,admitted,OperationKind.CHAT,8,"verification"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            state.verifications.recordStarted("verification-r3",admitted.root().initialRun().id().value(),
                    admitted.root().taskId().value(),0,"verify","WORKSPACE_CONTENT_SHA256_V1",
                    "a".repeat(64));
            assertEquals("CONTEXT_VERIFICATION_RUNNING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            state.verifications.markFailed("verification-r3",1,"","failed",1);
            state.repairAttempts.recordStarted("repair-r3",admitted.root().initialRun().id().value(),
                    admitted.root().taskId().value(),1,"verification-r3","unresolved");
            assertEquals("CONTEXT_REPAIR_RUNNING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }

    @Test void toolContinuationWithoutExactRunningRepairSourceRemainsRejected()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var context=publishToolContinuation(state,admitted,"unbound-repair");
            var root=admitted.root();
            state.verifications.recordStarted("verification-unbound",root.initialRun().id().value(),
                    root.taskId().value(),0,"verify","WORKSPACE_CONTENT_SHA256_V1","a".repeat(64));
            state.verifications.markFailed("verification-unbound",1,"","failed",1);
            state.repairAttempts.recordStarted("repair-unbound",root.initialRun().id().value(),
                    root.taskId().value(),1,"verification-unbound","unresolved");
            var occurrence=admitted.guard().admitOperationOccurrence("2","provider-unbound-repair",
                    "PROVIDER",OperationKind.TOOL_CONTINUATION.name(),
                    FencedExecutionGuard.canonicalInputHash("unbound-repair"));

            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->state.contexts.requireProviderAdmission(context.contextGenerationId(),
                            context.pointerVersion(),occurrence.value()));
            assertEquals("CONTEXT_REPAIR_RUNNING",failure.getMessage());
        }
    }

    @Test void multipleRunningRepairAttemptsCannotAuthorizeToolContinuation()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            state.verifications.recordStarted("verification-multiple",root.initialRun().id().value(),
                    root.taskId().value(),0,"verify","WORKSPACE_CONTENT_SHA256_V1","a".repeat(64));
            state.verifications.markFailed("verification-multiple",1,"","failed",1);
            state.repairAttempts.recordStarted("repair-multiple-1",root.initialRun().id().value(),
                    root.taskId().value(),1,"verification-multiple","unresolved");
            state.repairAttempts.recordStarted("repair-multiple-2",root.initialRun().id().value(),
                    root.taskId().value(),2,"verification-multiple","unresolved");
            var context=publishToolContinuation(state,admitted,"multiple-repairs");
            var occurrence=admitted.guard().admitOperationOccurrence("2","provider-multiple-repairs",
                    "PROVIDER",OperationKind.TOOL_CONTINUATION.name(),
                    FencedExecutionGuard.canonicalInputHash("multiple-repairs"));

            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->state.contexts.requireProviderAdmission(context.contextGenerationId(),
                            context.pointerVersion(),occurrence.value()));
            assertEquals("CONTEXT_REPAIR_RUNNING",failure.getMessage());
        }
    }

    @Test void wrongRunAndStaleRepairSourcesCannotEnterCurrentContextAuthority()throws Exception{
        Path otherRoot=workspace.resolve("other-run");Files.createDirectories(otherRoot);
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var root=admitted.root();
            state.verifications.recordStarted("verification-current",root.initialRun().id().value(),
                    root.taskId().value(),0,"verify","WORKSPACE_CONTENT_SHA256_V1","a".repeat(64));
            state.verifications.markFailed("verification-current",1,"","failed",1);
            state.repairAttempts.recordStarted("repair-current",root.initialRun().id().value(),
                    root.taskId().value(),1,"verification-current","unresolved");
            state.repairAttempts.markFailed("repair-current");

            var other=R3ContextTestFixtures.admit(state,otherRoot,"other-owner","other-lease");
            var otherRootAdmission=other.root();
            state.verifications.recordStarted("verification-other",
                    otherRootAdmission.initialRun().id().value(),otherRootAdmission.taskId().value(),
                    0,"verify","WORKSPACE_CONTENT_SHA256_V1","b".repeat(64));
            state.verifications.markFailed("verification-other",1,"","failed",1);
            state.repairAttempts.recordStarted("repair-other",
                    otherRootAdmission.initialRun().id().value(),otherRootAdmission.taskId().value(),
                    1,"verification-other","unresolved");
            try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"))){
                var adapter=new ContextSemanticSourceAdapter(database.getConnection());
                var owner=new ContextSemanticSourceAdapter.Owner(root.taskId().value(),
                        root.initialRun().id().value(),null);
                var wrongRun=new SourceReference("REPAIR_ITERATION","repair-other",
                        RepairAttemptStore.STATUS_RUNNING,"0".repeat(64),0);
                var wrongRunFailure=assertThrows(ContextSemanticSourceAdapter.SemanticFailure.class,
                        ()->adapter.renderReference(owner,wrongRun));
                assertEquals("CONTEXT_IDENTITY_MISMATCH",wrongRunFailure.getMessage());
                var staleAttempt=new SourceReference("REPAIR_ITERATION","repair-current",
                        RepairAttemptStore.STATUS_RUNNING,"0".repeat(64),0);
                var staleFailure=assertThrows(ContextSemanticSourceAdapter.SemanticFailure.class,
                        ()->adapter.renderReference(owner,staleAttempt));
                assertEquals("CONTEXT_IDENTITY_MISMATCH",staleFailure.getMessage());
            }
        }
    }

    @Test void steeringCandidateRejectsCompaction()throws Exception{
        assertSteeringStateRejects(SteeringModels.State.CANDIDATE);
    }

    @Test void steeringNeedsConfirmationRejectsCompaction()throws Exception{
        assertSteeringStateRejects(SteeringModels.State.NEEDS_CONFIRMATION);
    }

    @Test void steeringQueuedRejectsCompaction()throws Exception{
        assertSteeringStateRejects(SteeringModels.State.QUEUED);
    }

    @Test void steeringDeferredRejectsCompaction()throws Exception{
        assertSteeringStateRejects(SteeringModels.State.DEFERRED);
    }

    @Test void steeringApplyingRejectsCompaction()throws Exception{
        assertSteeringStateRejects(SteeringModels.State.APPLYING);
    }
    @Test void authoritativeHookRunningRejectsAndTerminalHookRestoresSafePoint()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"hook-authority"));
            var pointer=state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    OperationKind.CHAT).orElseThrow();
            state.hookRuns.recordStarted("hook-r3",root.initialRun().id().value(),root.taskId().value(),
                    "hook-id",HookRunStore.EVENT_BEFORE_FINISH,"occurrence",0,"echo safe",
                    HookRunStore.POLICY_FAIL);
            assertEquals("CONTEXT_HOOK_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            state.hookRuns.markPassed("hook-r3",0,"ok",null,1);
            assertTrue(state.contexts.safePoint(pointer,authority(admitted)).safe());
        }
    }

    @Test void realPlanAttemptRunningAndUnknownAuthoritiesBothReject()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"plan-attempt-authority"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            activatePlan(state,admitted.guard().current().snapshot());
            var started=state.planExecutions.startNextReadyAttempt(
                    admitted.guard().current().snapshot(),Instant.now(),1,"r3-evidence");
            assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());
            assertEquals("CONTEXT_PLAN_STEP_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            assertEquals(PlanExecutionStore.Status.SUCCESS,state.planExecutions.completeAttempt(
                    admitted.guard().current().snapshot(),Instant.now(),started.value().attempt(),
                    Outcome.UNKNOWN,2,started.value().step().transitionVersion(),
                    "UNKNOWN_EXTERNAL_EFFECT","effect outcome unresolved").status());
            assertEquals("CONTEXT_PLAN_STEP_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }

    @Test void existingMcpEffectAuthorityPendingRunningAndUnknownAllReject()throws Exception{
        Path root=workspace.resolve("mcp-authority");Files.createDirectories(root);
        Path executable=Files.writeString(root.resolve("server.exe"),"server");
        Path working=Files.createDirectory(root.resolve("work"));
        McpConfigManifest manifest=new McpConfigManifestBuilder().build(new McpServerConfig(
                "srv",true,"STDIO",executable,List.of("--stdio"),working,List.of(),
                McpRuntimeLimits.defaults(),List.of("lookup"),
                List.of(McpServerConfig.PROTOCOL_VERSION)));
        try(StateStore state=new StateStore(root)){
            var admitted=R3ContextTestFixtures.admit(state,root);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    root,admitted,OperationKind.CHAT,8,"mcp-effect-authority"));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            var saved=state.mcpServerManifests.saveOrFindManifest(manifest);
            state.mcpSessions.createSession("session-r3",saved.manifestId(),"srv","run","occ",1);
            String catalogHash="b".repeat(64),schemaHash="c".repeat(64);
            state.mcpToolCatalogs.createBuildingCatalog("catalog-r3","session-r3",saved.manifestId(),
                    "srv",McpServerConfig.PROTOCOL_VERSION,catalogHash,1,2);
            state.mcpToolCatalogs.saveCatalogToolsAtomically("catalog-r3",List.of(
                    new McpToolStore.ToolRecord("tool-r3","catalog-r3","srv","lookup",
                            "mcp.srv.lookup","lookup",null,"{}",schemaHash,"EXPOSED",null)),
                    catalogHash);
            state.mcpToolCatalogs.freezeCatalog("catalog-r3",catalogHash);
            state.mcpToolCalls.createToolCall(new McpToolCallStore.CallCreate("call-r3","session-r3",
                    "catalog-r3","tool-r3",admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),"effect",1,null,manifest.configHash(),
                    catalogHash,schemaHash,"mcp.srv.lookup","lookup","d".repeat(64),
                    "fields=[]","PENDING"));
            assertEquals("CONTEXT_TOOL_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            state.mcpToolCalls.approveAndMarkRunning("call-r3","request-r3");
            assertEquals("CONTEXT_TOOL_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
            assertTrue(state.mcpToolCalls.markUnknownOutcome("call-r3"));
            assertEquals("CONTEXT_TOOL_EFFECT_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }

    @Test void staleContextPointerVersionRejectsThePreviouslyObservedTarget()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"pointer-v1"));
            var stale=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"pointer-v2"));
            assertEquals("CONTEXT_POINTER_CONFLICT",
                    state.contexts.safePoint(stale,authority(admitted)).code());
        }
    }

    private void assertCallerMutationRejected(String marker,
            UnaryOperator<ExecutionAuthoritySnapshot> change)throws Exception{
        Path root=workspace.resolve(marker);
        try(StateStore state=new StateStore(root)){
            var admitted=R3ContextTestFixtures.admit(state,root);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    root,admitted,OperationKind.CHAT,8,marker));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(pointer,change.apply(authority(admitted))).code());
        }
    }

    private ContextGenerationStore.Published publishToolContinuation(StateStore state,
            R3ContextTestFixtures.Admitted admitted,String marker)throws Exception{
        var root=admitted.root();
        AgentMessage user=AgentMessage.userGoal("goal "+marker);
        String firstId="call-a-"+marker,secondId="call-b-"+marker;
        String toolJson="[{\"id\":\""+firstId+"\",\"type\":\"function\",\"function\":{"
                +"\"name\":\"write_file\",\"arguments\":\"{}\"}},{\"id\":\""+secondId
                +"\",\"type\":\"function\",\"function\":{\"name\":\"write_file\","
                +"\"arguments\":\"{}\"}}]";
        AgentMessage action=AgentMessage.actionWithToolCall("action "+marker,firstId,toolJson);
        AgentMessage first=AgentMessage.observationForToolCall(
                SharedToolBatchPolicy.notExecutedResult(1,2),firstId);
        AgentMessage second=AgentMessage.observationForToolCall(
                SharedToolBatchPolicy.notExecutedResult(2,2),secondId);
        admitted.guard().appendEvent(0,user);
        admitted.guard().appendEvent(1,action);
        admitted.guard().appendEvent(1,first);
        admitted.guard().appendEvent(1,second);
        Identity identity=new Identity(SavedSessionService.workspaceIdentity(workspace),
                root.threadId().value(),root.taskId().value(),root.initialRun().id().value(),
                root.generation().id().value(),CanonicalId.newId(EntityFamily.CONTEXT).value(),
                root.instructions().manifest().canonicalHash(),null,null);
        ContextInvocation invocation=new ContextInvocation(identity,OperationKind.TOOL_CONTINUATION,
                "2",null,List.of(),null,null,null,"mock","model",
                new CapabilityProfile("mock",131_072,LimitProvenance.PROVIDER_REPORTED_LIMIT,
                        128,32),false,DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(invocation,
                "synthetic repair authority",List.of(user,action,first,second),List.of(),
                admitted.guard().current()));
        var compiled=new DeterministicContextCompiler().compile(new CompileRequest(
                assembled.invocation(),assembled.sources(),assembled.tools()));
        return state.contexts.publishBase(compiled);
    }

    private void assertSteeringStateRejects(SteeringModels.State desired)throws Exception{
        Path root=workspace.resolve("steering-"+desired.name().toLowerCase());
        try(StateStore state=new StateStore(root)){
            var admitted=R3ContextTestFixtures.admit(state,root);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    root,admitted,OperationKind.CHAT,8,desired.name()));
            var pointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            var target=state.steerings.captureTarget(admitted.root().taskId(),
                    admitted.root().initialRun().id()).target();
            if(desired==SteeringModels.State.CANDIDATE){
                insertSteeringCandidate(root,target);
                assertEquals("CONTEXT_STEERING_PENDING",
                        state.contexts.safePoint(pointer,authority(admitted)).code());
                return;
            }
            var pending=state.steerings.admit(target,new SteeringClassifier().classify("ambiguous change"),
                    Provenance.INTERACTIVE_EXPLICIT,"owner","session",Instant.now());
            assertEquals(SteeringStore.Status.NEEDS_CONFIRMATION,pending.status());
            if(desired==SteeringModels.State.QUEUED||desired==SteeringModels.State.DEFERRED
                    ||desired==SteeringModels.State.APPLYING){
                var queued=state.steerings.confirm(pending.steeringId(),
                        SteeringModels.ConfirmationDecision.CONFIRM_NON_MATERIAL,
                        "owner","session",pending.queue().version(),Instant.now());
                assertEquals(SteeringStore.Status.SUCCESS,queued.status());
            }
            if(desired!=SteeringModels.State.NEEDS_CONFIRMATION
                    &&desired!=SteeringModels.State.QUEUED){
                try(var database=new DatabaseManager(root.resolve(".paicli/state"));
                    var update=database.getConnection().prepareStatement("""
                      UPDATE steering_queue_authority SET state=?,queue_sequence=?,
                        queue_version=queue_version+1,reason_code='R3_SAFE_POINT_FIXTURE',updated_at=?
                      WHERE steering_id=?
                      """)){
                    update.setString(1,desired.name());
                    update.setLong(2,pending.queue().sequence()==null?1:pending.queue().sequence());
                    update.setString(3,Instant.now().toString());
                    update.setString(4,pending.steeringId().value());
                    assertEquals(1,update.executeUpdate());
                }
            }
            assertEquals("CONTEXT_STEERING_PENDING",
                    state.contexts.safePoint(pointer,authority(admitted)).code());
        }
    }

    private static void insertSteeringCandidate(Path root,SteeringModels.Target target)throws Exception{
        CanonicalId steering=CanonicalId.newId(EntityFamily.STEERING);
        var classified=new SteeringClassifier().classify("ambiguous change");
        String at=Instant.now().toString();
        try(var database=new DatabaseManager(root.resolve(".paicli/state"));
            var definition=database.getConnection().prepareStatement("""
              INSERT INTO steering_definitions(steering_id,thread_id,task_id,target_run_id,
                target_plan_revision_id,target_plan_hash,target_plan_pointer_version,
                target_generation_id,target_context_pointer_version,content,content_hash,
                provenance,input_owner,input_session_id,created_at)
              VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
              """);
            var classification=database.getConnection().prepareStatement("""
              INSERT INTO steering_classification_facts(steering_id,fact_number,task_id,
                classification,outcome,classifier_version,reason_code,created_at)
              VALUES(?,1,?,?,?,?,?,?)
              """);
            var queue=database.getConnection().prepareStatement("""
              INSERT INTO steering_queue_authority(steering_id,task_id,target_run_id,
                target_plan_revision_id,target_plan_hash,target_plan_pointer_version,
                target_generation_id,target_context_pointer_version,state,queue_sequence,
                queue_version,reason_code,updated_at)
              VALUES(?,?,?,?,?,?,?,?,'CANDIDATE',NULL,0,'ADMITTED',?)
              """);
            var fact=database.getConnection().prepareStatement("""
              INSERT INTO steering_state_facts(steering_id,state_number,task_id,target_run_id,
                queue_version,state,reason_code,recorded_at)
              VALUES(?,1,?,?,0,'CANDIDATE','ADMITTED',?)
              """)){
            definition.setString(1,steering.value());definition.setString(2,target.threadId().value());
            definition.setString(3,target.taskId().value());definition.setString(4,target.runId().value());
            definition.setString(5,target.planRevisionId()==null?null:target.planRevisionId().value());
            definition.setString(6,target.planHash());
            if(target.planPointerVersion()==null)definition.setNull(7,java.sql.Types.BIGINT);
            else definition.setLong(7,target.planPointerVersion());
            definition.setString(8,target.generationId().value());
            definition.setLong(9,target.contextPointerVersion());definition.setString(10,classified.content());
            definition.setString(11,classified.contentHash());
            definition.setString(12,Provenance.INTERACTIVE_EXPLICIT.name());
            definition.setString(13,"owner");definition.setString(14,"session");definition.setString(15,at);
            definition.executeUpdate();
            classification.setString(1,steering.value());classification.setString(2,target.taskId().value());
            classification.setString(3,classified.classification().name());
            classification.setString(4,classified.outcome().name());
            classification.setString(5,SteeringClassifier.VERSION);
            classification.setString(6,classified.reasonCode());classification.setString(7,at);
            classification.executeUpdate();
            queue.setString(1,steering.value());queue.setString(2,target.taskId().value());
            queue.setString(3,target.runId().value());
            queue.setString(4,target.planRevisionId()==null?null:target.planRevisionId().value());
            queue.setString(5,target.planHash());
            if(target.planPointerVersion()==null)queue.setNull(6,java.sql.Types.BIGINT);
            else queue.setLong(6,target.planPointerVersion());
            queue.setString(7,target.generationId().value());queue.setLong(8,target.contextPointerVersion());
            queue.setString(9,at);queue.executeUpdate();
            fact.setString(1,steering.value());fact.setString(2,target.taskId().value());
            fact.setString(3,target.runId().value());fact.setString(4,at);fact.executeUpdate();
        }
    }

    private static ExecutionAuthoritySnapshot authority(R3ContextTestFixtures.Admitted admitted){
        return admitted.guard().current().snapshot();
    }

    private static Revision activatePlan(StateStore state,
            com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot authority){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        StepDefinition definition=new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,"execute","bounded",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ,
                List.of(exactReadRequirement()),"b".repeat(64));
        Revision draft=new Revision(plan,authority.taskId(),1,"goal","accept","","","verify",
                "0".repeat(64),"USER","r3-evidence",authority.runId(),Instant.now(),
                List.of(definition),List.of(),List.of(),Revision.CANONICAL_SCHEMA_V3);
        Revision revision=new Revision(plan,authority.taskId(),1,draft.goal(),draft.acceptanceCriteria(),
                draft.assumptions(),draft.risks(),draft.verificationStrategy(),PlanCanonicalizer.hash(draft),
                draft.sourceType(),draft.provenance(),authority.runId(),draft.createdAt(),draft.steps(),
                draft.dependencies(),draft.lineage(),Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.propose(
                authority,Instant.now(),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(
                authority,Instant.now(),plan,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                authority,Instant.now(),plan,1,0,"approval-r3","user","r3",null).status());
        return revision;
    }
    private static StructuredPlanModels.ToolRequirement exactReadRequirement(){
        String json="{\"path\":\"AGENTS.md\",\"maxLines\":null,"
                +"\"includeInternal\":false}";
        var selector=new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH,json,
                PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement("read-agents",0,"read_file",
                PlanCompletionModels.EffectClass.READ,selector,1,1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }
    private long scalar(String sql)throws Exception{
        try(var database=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=database.getConnection().createStatement();var row=statement.executeQuery(sql)){
            return row.next()?row.getLong(1):-1;
        }
    }
}
