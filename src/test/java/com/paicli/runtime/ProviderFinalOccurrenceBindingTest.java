package com.paicli.runtime;

import com.paicli.identity.CanonicalId;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FencedMutationStore;
import com.paicli.state.StateStore;
import com.paicli.state.VerificationStore;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProviderFinalOccurrenceBindingTest {
    private static final String DIGEST="a".repeat(64);
    private static final String REPAIRED_DIGEST="b".repeat(64);

    @TempDir Path workspace;

    @Test
    void acceptedFinalCompletesExactOccurrenceAndMovesRunAtomically() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var guard=admitted.guard();
            CanonicalId occurrence=guard.admitOperationOccurrence("1","provider-final-1",
                    "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input"));

            guard.acceptProviderFinal(occurrence,1,CodingRunStore.PHASE_EXECUTING,
                    "exact final",DIGEST);

            var stored=state.r1aDurable.findOccurrence(occurrence).orElseThrow();
            assertEquals("COMPLETED",stored.status());
            assertEquals(DIGEST,stored.finalCandidateDigest());
            assertEquals(FencedMutationStore.providerFinalResultHash("exact final"),
                    stored.resultHash());
            assertEquals(CodingRunStore.PHASE_VERIFYING,state.codingRuns.findById(
                    admitted.root().initialRun().id().value()).orElseThrow().phase());
            assertEquals("exact final",singleFinal(state,admitted.root().taskId().value()));
        }
    }

    @Test
    void latePhaseFailureRollsBackFinalEventAndOccurrenceCompletion() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var guard=admitted.guard();
            CanonicalId occurrence=guard.admitOperationOccurrence("1","provider-final-rollback",
                    "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input"));
            state.codingRuns.transitionPhase(admitted.root().initialRun().id().value(),
                    CodingRunStore.PHASE_EXECUTING,CodingRunStore.PHASE_VERIFYING);

            assertThrows(FencedExecutionGuard.AuthorityLostException.class,()->
                    guard.acceptProviderFinal(occurrence,1,CodingRunStore.PHASE_EXECUTING,
                            "must roll back",DIGEST));

            assertEquals("STARTED",state.r1aDurable.findOccurrence(occurrence)
                    .orElseThrow().status());
            assertNull(state.r1aDurable.findOccurrence(occurrence)
                    .orElseThrow().finalCandidateDigest());
            assertTrue(state.events.findMessages(admitted.root().taskId().value()).stream()
                    .noneMatch(message->message.type()==com.paicli.model.AgentMessage.MessageType.FINAL));
        }
    }

    @Test
    void planlessResultAcceptsPassedOrConfiguredUnverifiedButNeverFailed() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var guard=admitted.guard();
            CanonicalId occurrence=guard.admitOperationOccurrence("1","provider-result",
                    "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input"));
            guard.acceptProviderFinal(occurrence,1,CodingRunStore.PHASE_EXECUTING,"done",DIGEST);

            var unverified=guard.persistPlanlessUserResult(occurrence,DIGEST,null,
                    "REQUIRED","done");
            assertNull(unverified.planRevisionId());
        }

        Path verifiedWorkspace=workspace.resolve("verified");
        try(StateStore state=new StateStore(verifiedWorkspace)){
            var admitted=R3ContextTestFixtures.admit(state,verifiedWorkspace);
            var guard=admitted.guard();
            CanonicalId occurrence=guard.admitOperationOccurrence("1","provider-result-verified",
                    "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input"));
            guard.acceptProviderFinal(occurrence,1,CodingRunStore.PHASE_EXECUTING,"done",DIGEST);
            guard.startVerification("failed",0,"verify",VerificationStore.STATUS_RUNNING,
                    WorkspaceCandidateDigest.KIND,DIGEST,null);
            guard.finishVerification("failed",VerificationStore.STATUS_FAILED,1,"","bad",1);
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,()->
                    guard.persistPlanlessUserResult(occurrence,DIGEST,"failed",
                            "REQUIRED","done"));
            guard.startVerification("passed",0,"verify",VerificationStore.STATUS_RUNNING,
                    WorkspaceCandidateDigest.KIND,DIGEST,null);
            guard.finishVerification("passed",VerificationStore.STATUS_PASSED,0,"ok",null,1);
            assertNotNull(guard.persistPlanlessUserResult(occurrence,DIGEST,"passed",
                    "REQUIRED","done"));
        }
    }

    @Test
    void repairedCandidateRequiresNewFinalOccurrenceAndMatchingVerification() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var guard=admitted.guard();
            CanonicalId stale=guard.admitOperationOccurrence("1","provider-final-before-repair",
                    "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input-1"));
            guard.acceptProviderFinal(stale,1,CodingRunStore.PHASE_EXECUTING,
                    "before repair",DIGEST);
            guard.transitionRunPhase(CodingRunStore.PHASE_VERIFYING,
                    CodingRunStore.PHASE_REPAIRING);

            CanonicalId repaired=guard.admitOperationOccurrence("2","provider-final-after-repair",
                    "PROVIDER","REPAIR",FencedExecutionGuard.canonicalInputHash("input-2"));
            guard.acceptProviderFinal(repaired,2,CodingRunStore.PHASE_REPAIRING,
                    "after repair",REPAIRED_DIGEST);
            guard.startVerification("repaired-pass",2,"verify",VerificationStore.STATUS_RUNNING,
                    WorkspaceCandidateDigest.KIND,REPAIRED_DIGEST,null);
            guard.finishVerification("repaired-pass",VerificationStore.STATUS_PASSED,
                    0,"ok",null,2);

            assertThrows(FencedExecutionGuard.AuthorityLostException.class,()->
                    guard.persistPlanlessUserResult(stale,DIGEST,"repaired-pass",
                            "REQUIRED","before repair"));
            assertNotNull(guard.persistPlanlessUserResult(repaired,REPAIRED_DIGEST,
                    "repaired-pass","REQUIRED","after repair"));
        }
    }

    private static String singleFinal(StateStore state,String taskId)throws Exception{
        var finals=state.events.findMessages(taskId).stream()
                .filter(message->message.type()==com.paicli.model.AgentMessage.MessageType.FINAL)
                .toList();
        assertEquals(1,finals.size());return finals.getFirst().content();
    }
}
