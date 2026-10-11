package com.paicli.state;

import com.paicli.config.Config;
import com.paicli.identity.*;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.runtime.*;
import com.paicli.state.FollowUpModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class R1eFollowUpStoreTest {
    @TempDir Path workspace;
    private static final Instant AT=Instant.parse("2026-07-20T00:00:00Z");

    @Test void explicitContinueAtomicallyCreatesIsolatedChildAndMovesHeadOnce()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"# current instructions\n");
        CanonicalId capsule;CanonicalId parent;CanonicalId child;
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"parent goal");parent=seed.task();ParentRow before=parentRow(parent);
            FollowUpService service=service(state);FollowUpService.Outcome outcome=service.begin(seed.thread(),
                    "continue with child work",true,"owner","session",AT.plusSeconds(1));
            assertEquals("ADMITTED",outcome.code());assertNotNull(outcome.child());capsule=outcome.candidate().capsuleId();child=outcome.child().taskId();
            assertNotEquals(parent,child);assertEquals(parent,boundParent(capsule));assertEquals(child,head(seed.thread()));
            assertEquals(before,parentRow(parent));assertEquals(0,count("SELECT count(*) FROM plan_revisions WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_evidence WHERE task_id=?",child.value()));
            assertEquals(1,count("SELECT count(*) FROM task_continuation_edges WHERE capsule_id=?",capsule.value()));
            assertThrows(SQLException.class,()->{try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
                    PreparedStatement s=m.getConnection().prepareStatement("UPDATE task_identities SET goal='rewritten' WHERE task_id=?")){
                s.setString(1,parent.value());s.executeUpdate();}});
            AdmissionResult retry=state.followUps.admitChild(capsule,outcome.candidate().version(),"unused",runtimeSpec(),AT.plusSeconds(2));
            assertTrue(retry instanceof Admitted admitted&&admitted.existing());
        }
        try(StateStore reopened=new StateStore(workspace)){
            Candidate durable=reopened.followUps.findCandidate(capsule).orElseThrow();
            assertEquals(CandidateState.ADMITTED,durable.state());assertEquals(child,durable.childTaskId());
        }
    }

    @Test void unknownBuildsNoCapsuleBeforeIntentConfirmationAndRiskUsesSeparateFact()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            FollowUpService.Outcome unknown=service.begin(seed.thread(),"please inspect this",false,"owner","session",AT.plusSeconds(1));
            assertEquals(CandidateState.NEEDS_INTENT_CONFIRMATION,unknown.candidate().state());
            assertEquals(0,count("SELECT count(*) FROM continuation_capsules WHERE capsule_id=?",unknown.candidate().capsuleId().value()));
            Files.writeString(workspace.resolve("AGENTS.md"),"after authority change\n");
            FollowUpService.Outcome risk=service.confirm(unknown.candidate().capsuleId(),ConfirmationKind.INTENT,
                    unknown.candidate().version(),"owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            assertEquals(CandidateState.NEEDS_RISK_CONFIRMATION,risk.candidate().state());assertNull(risk.child());
            assertEquals(1,count("SELECT count(*) FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind='INTENT'",
                    unknown.candidate().capsuleId().value()));
            FollowUpService.Outcome admitted=service.confirm(risk.candidate().capsuleId(),ConfirmationKind.RISK,
                    risk.candidate().version(),"owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            assertNotNull(admitted.child());assertEquals(1,count("SELECT count(*) FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind='RISK'",
                    unknown.candidate().capsuleId().value()));
        }
    }

    @Test void failedTerminalIsNotFollowUpEligible()throws Exception{
        try(StateStore state=new StateStore(workspace)){CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,config(),RuntimeManifest.disabled(),AT);
            terminal(task,root.initialRun().id(),"FAILED");assertTrue(state.followUps.currentEligibleParent(root.threadId()).isEmpty());}
    }

    @Test void twoCandidatesForSameParentProduceOneChildAndDurableHeadConflict()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var a=service.begin(seed.thread(),"ambiguous one",false,"owner","session",AT.plusSeconds(1));
            var b=service.begin(seed.thread(),"ambiguous two",false,"owner","session",AT.plusSeconds(1));
            Files.writeString(workspace.resolve("AGENTS.md"),"changed\n");
            a=service.confirm(a.candidate().capsuleId(),ConfirmationKind.INTENT,a.candidate().version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            b=service.confirm(b.candidate().capsuleId(),ConfirmationKind.INTENT,b.candidate().version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            var winner=service.confirm(a.candidate().capsuleId(),ConfirmationKind.RISK,a.candidate().version(),"owner","session",
                    ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            var loser=service.confirm(b.candidate().capsuleId(),ConfirmationKind.RISK,b.candidate().version(),"owner","session",
                    ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            assertNotNull(winner.child());assertNull(loser.child());assertEquals("HEAD_CONFLICT",loser.code());
            assertEquals(CandidateState.HEAD_CONFLICT,loser.candidate().state());
            assertEquals(1,count("SELECT count(*) FROM task_continuation_edges WHERE parent_task_id=?",seed.task().value()));
            assertEquals(0,count("SELECT count(*) FROM task_identities WHERE parent_task_id=? AND task_id<>?",
                    seed.task().value(),winner.child().taskId().value()));
        }
    }

    @Test void instructionDriftAfterPrepareTerminatesOldCandidateAndRebuildsFreshRiskFacts()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"parent\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var unknown=service.begin(seed.thread(),"ambiguous",false,"owner","session",AT.plusSeconds(1));
            Files.writeString(workspace.resolve("AGENTS.md"),"first change\n");
            var risk=service.confirm(unknown.candidate().capsuleId(),ConfirmationKind.INTENT,unknown.candidate().version(),
                    "owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            Files.writeString(workspace.resolve("AGENTS.md"),"second change\n");
            var rebuilt=service.confirm(risk.candidate().capsuleId(),ConfirmationKind.RISK,risk.candidate().version(),
                    "owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            assertNotEquals(risk.candidate().capsuleId(),rebuilt.candidate().capsuleId());
            assertEquals(CandidateState.FAILED,state.followUps.findCandidate(risk.candidate().capsuleId()).orElseThrow().state());
            state.followUps.markPayloadRetained(risk.candidate().capsuleId());
            assertEquals(1,count("SELECT count(*) FROM continuation_capsules WHERE capsule_id=?",risk.candidate().capsuleId().value()));
            assertTrue(count("SELECT count(*) FROM continuation_capsule_facts WHERE capsule_id=?",risk.candidate().capsuleId().value())>0);
            assertEquals(CandidateState.NEEDS_RISK_CONFIRMATION,rebuilt.candidate().state());assertNull(rebuilt.child());
        }
    }

    @Test void childAdmissionFailureRollsBackTaskRunGenerationManifestEdgeAndHead()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"parent\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var unknown=service.begin(seed.thread(),"ambiguous",false,"owner","session",AT.plusSeconds(1));
            Files.writeString(workspace.resolve("AGENTS.md"),"changed\n");
            var risk=service.confirm(unknown.candidate().capsuleId(),ConfirmationKind.INTENT,unknown.candidate().version(),
                    "owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement s=m.getConnection().createStatement()){
                s.execute("CREATE TRIGGER test_r1e_abort BEFORE INSERT ON capsule_child_bindings BEGIN SELECT RAISE(ABORT,'injected'); END");}
            var failed=service.confirm(risk.candidate().capsuleId(),ConfirmationKind.RISK,risk.candidate().version(),
                    "owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            assertEquals("CHILD_ADMISSION_PERSISTENCE_FAILURE",failed.code());assertNull(failed.child());assertEquals(seed.task(),head(seed.thread()));
            assertEquals(0,count("SELECT count(*) FROM task_identities WHERE parent_task_id=?",seed.task().value()));
            assertEquals(0,count("SELECT count(*) FROM task_continuation_edges WHERE parent_task_id=?",seed.task().value()));
        }
    }

    @Test void advisoryChildStartsWithoutInheritedOrAutoGeneratedPlan()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"same authority\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"parent goal");
            var outcome=service(state,config(true)).begin(seed.thread(),"continue with new child goal",true,
                    "owner","session",AT.plusSeconds(1));
            assertNotNull(outcome.child());CanonicalId child=outcome.child().taskId();
            assertEquals("PLAN_DISABLED",text("SELECT applicability FROM task_plan_applicability WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_revisions WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_step_definitions WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_step_executions WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_step_attempts WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_evidence WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM task_plan_authorities WHERE task_id=?",child.value()));
            assertEquals(0,count("SELECT count(*) FROM plan_run_bindings WHERE task_id=?",child.value()));
        }
    }

    @Test void frozenHeadMismatchCreatesNoCandidateOrCapsule()throws Exception{
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");
            FollowUpService.FollowUpException stale=assertThrows(FollowUpService.FollowUpException.class,()->service(state).begin(
                    seed.thread(),"continue",true,"owner","session",AT.plusSeconds(1),seed.task(),99));
            assertEquals("STALE_HEAD",stale.getMessage());
            assertEquals(0,count("SELECT count(*) FROM followup_candidate_definitions WHERE parent_task_id=?",seed.task().value()));
        }
    }

    @Test void candidateContinuesAcrossProcessAndCapsuleCorruptionFailsClosed()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");CanonicalId capsule;CanonicalId thread;
        try(StateStore first=new StateStore(workspace)){Seed seed=completedParent(first,"goal");thread=seed.thread();
            var pending=service(first).begin(thread,"ambiguous wording",false,"owner","session",AT.plusSeconds(1));
            assertEquals(CandidateState.NEEDS_INTENT_CONFIRMATION,pending.candidate().state());capsule=pending.candidate().capsuleId();
        }
        CanonicalId child;
        try(StateStore second=new StateStore(workspace)){Candidate pending=second.followUps.findCandidate(capsule).orElseThrow();
            var admitted=service(second).confirm(capsule,ConfirmationKind.INTENT,pending.version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            assertNotNull(admitted.child());child=admitted.child().taskId();
            var confirmedAgain=service(second).confirm(capsule,ConfirmationKind.INTENT,pending.version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            assertEquals("ADMITTED_EXISTING",confirmedAgain.code());assertEquals(child,confirmedAgain.child().taskId());
            AdmissionResult retry=second.followUps.admitChild(capsule,admitted.candidate().version(),"unused",runtimeSpec(),AT.plusSeconds(3));
            assertTrue(retry instanceof Admitted existing&&existing.existing());
        }
        try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement s=m.getConnection().createStatement()){
            s.execute("DROP TRIGGER r1e_capsule_immutable");
            s.executeUpdate("UPDATE continuation_capsules SET canonical_hash='"+"0".repeat(64)+"' WHERE capsule_id='"+capsule.value()+"'");
        }
        try(StateStore third=new StateStore(workspace)){
            assertFalse(third.followUps.childOriginIntegrityValid(child));
            assertThrows(RuntimeException.class,()->third.followUps.findAdmittedChild(capsule));
        }
    }

    @Test void admittedChildInitialRunIsClaimedBeforeWorkerAndExpiredClaimIsTakenOverOnce()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");
            var admitted=service(state).begin(seed.thread(),"child work",true,"owner","session",AT.plusSeconds(1));
            assertEquals("START_PENDING",text("SELECT state FROM child_initial_run_launches WHERE child_task_id=?",
                    admitted.child().taskId().value()));
            var first=state.followUps.claimInitialChildStart(admitted.child(),"process-a","lease-a",
                    AT.plusSeconds(2),AT.plusSeconds(12),Duration.ZERO);
            assertEquals(ChildStartStatus.CLAIMED,first.status());
            assertEquals(ChildStartStatus.ALREADY_CLAIMED,state.followUps.claimInitialChildStart(admitted.child(),
                    "process-b","lease-b",AT.plusSeconds(3),AT.plusSeconds(13),Duration.ZERO).status());
            assertTrue(state.followUps.markInitialChildStarted(first.claim(),AT.plusSeconds(4)));
            assertEquals(ChildStartStatus.ALREADY_STARTED,state.followUps.claimInitialChildStart(admitted.child(),
                    "process-b","lease-b",AT.plusSeconds(5),AT.plusSeconds(15),Duration.ZERO).status());
            var takeover=state.followUps.claimInitialChildStart(admitted.child(),"process-b","lease-b",
                    AT.plusSeconds(12),AT.plusSeconds(22),Duration.ZERO);
            assertEquals(ChildStartStatus.CLAIMED,takeover.status());
            assertEquals(admitted.child().runId(),takeover.claim().child().runId());
            assertFalse(state.followUps.markInitialChildStarted(first.claim(),AT.plusSeconds(13)));
            assertTrue(state.followUps.markInitialChildStarted(takeover.claim(),AT.plusSeconds(13)));
            assertEquals(1,count("SELECT count(*) FROM task_ownership_leases WHERE task_id=?",
                    admitted.child().taskId().value()));
        }
    }

    @Test void rejectedWorkerClaimIsImmediatelyRecoverableBySameChildAndRun()throws Exception{
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");
            var admitted=service(state).begin(seed.thread(),"child work",true,"owner","session",AT.plusSeconds(1));
            var first=state.followUps.claimInitialChildStart(admitted.child(),"process-a","lease-a",
                    AT.plusSeconds(2),AT.plusSeconds(30),Duration.ZERO);
            assertTrue(state.followUps.releaseInitialChildStartClaim(first.claim(),"EXECUTOR_REJECTED",AT.plusSeconds(3)));
            var session=new SavedSessionService(state,workspace,Clock.fixed(AT.plusSeconds(3),ZoneOffset.UTC))
                    .select(seed.thread().value());
            assertNotNull(session.view());
            assertEquals(SessionBinding.State.BOUND_IDLE,session.view().binding().state());
            var recovered=state.followUps.claimInitialChildStart(admitted.child(),"process-b","lease-b",
                    AT.plusSeconds(3),AT.plusSeconds(33),Duration.ZERO);
            assertEquals(ChildStartStatus.CLAIMED,recovered.status());
            assertEquals(admitted.child().taskId(),recovered.claim().child().taskId());
            assertTrue(recovered.claim().lease().fencingToken()>first.claim().lease().fencingToken());
        }
    }

    @Test void staleIntentPreparingAndReadyCandidatesBecomeTerminalHeadConflictsAndDoNotBlockPending()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");ParentAnchor parent=state.followUps
                    .currentEligibleParent(seed.thread()).orElseThrow();
            var unknown=service(state).begin(seed.thread(),"please inspect this",false,"owner","session",AT.plusSeconds(1));
            Candidate preparing=state.followUps.createCandidate(parent,"continue preparing",
                    new FollowUpCanonicalizer().sha256("continue preparing"),"EXPLICIT_CONTINUE","owner","session",
                    new IntentClassification(Intent.CONTINUE_CURRENT_THREAD,FollowUpClassifier.VERSION,"TEST"),AT.plusSeconds(2));
            var readyUnknown=service(state).begin(seed.thread(),"please continue ready",false,"owner","session",AT.plusSeconds(2));
            Files.writeString(workspace.resolve("AGENTS.md"),"changed\n");
            var risk=service(state).confirm(readyUnknown.candidate().capsuleId(),ConfirmationKind.INTENT,
                    readyUnknown.candidate().version(),"owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,
                    AT.plusSeconds(3));
            assertEquals(CandidateState.NEEDS_RISK_CONFIRMATION,risk.candidate().state());
            Candidate ready=state.followUps.confirm(risk.candidate().capsuleId(),ConfirmationKind.RISK,
                    risk.candidate().version(),"owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            assertEquals(CandidateState.READY_TO_ADMIT,ready.state());
            Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
            var winner=service(state).begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(4));
            assertNotNull(winner.child());
            var stale=service(state).confirm(unknown.candidate().capsuleId(),ConfirmationKind.INTENT,
                    unknown.candidate().version(),"owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(5));
            assertEquals(CandidateState.HEAD_CONFLICT,stale.candidate().state());
            assertTrue(state.followUps.findPending(seed.thread()).isEmpty());
            assertEquals(CandidateState.HEAD_CONFLICT,state.followUps.findCandidate(preparing.capsuleId()).orElseThrow().state());
            assertEquals(CandidateState.HEAD_CONFLICT,state.followUps.findCandidate(ready.capsuleId()).orElseThrow().state());
            assertEquals(3,count("SELECT count(*) FROM followup_admission_facts WHERE result='HEAD_CONFLICT' AND observed_head_task_id=?",
                    winner.child().taskId().value()));
        }
    }

    @Test void readyManifestStaleRaceResolvesHeadConflictInSingleStoreTransaction()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var unknown=service.begin(seed.thread(),"ambiguous",false,"owner","session",AT.plusSeconds(1));
            Files.writeString(workspace.resolve("AGENTS.md"),"changed\n");
            var risk=service.confirm(unknown.candidate().capsuleId(),ConfirmationKind.INTENT,unknown.candidate().version(),
                    "owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            Candidate ready=state.followUps.confirm(risk.candidate().capsuleId(),ConfirmationKind.RISK,risk.candidate().version(),
                    "owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3));
            assertEquals(CandidateState.READY_TO_ADMIT,ready.state());
            Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
            CountDownLatch atBoundary=new CountDownLatch(1),release=new CountDownLatch(1);
            try(DatabaseManager racingManager=new DatabaseManager(workspace.resolve(".paicli/state"))){
                FollowUpStore racing=new FollowUpStore(racingManager.getConnection(),(point,candidate)->{
                    if("BEFORE_AUTHORITY_LOCK".equals(point)){atBoundary.countDown();
                        if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("test barrier timed out");}
                });
                ExecutorService executor=Executors.newSingleThreadExecutor();
                try{
                    Future<Candidate> stale=executor.submit(()->racing.failStaleManifest(ready.capsuleId(),ready.version(),AT.plusSeconds(5)));
                    assertTrue(atBoundary.await(10,TimeUnit.SECONDS));
                    var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(4));
                    assertNotNull(winner.child());release.countDown();
                    Candidate terminal=stale.get(10,TimeUnit.SECONDS);
                    assertEquals(CandidateState.HEAD_CONFLICT,terminal.state());
                    assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='FAILED'",
                            ready.capsuleId().value()));
                    assertEquals(1,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='HEAD_CONFLICT'",
                            ready.capsuleId().value()));
                    assertEquals(0,count("SELECT count(*) FROM task_identities WHERE parent_task_id=? AND task_id<>?",
                            seed.task().value(),winner.child().taskId().value()));
                }finally{release.countDown();executor.shutdownNow();}
            }
        }
    }

    @Test void preparingLocalFailureRaceStillResolvesAuthorityLossFirst()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");
            ParentAnchor parent=state.followUps.currentEligibleParent(seed.thread()).orElseThrow();
            Candidate preparing=state.followUps.createCandidate(parent,"continue preparing",
                    new FollowUpCanonicalizer().sha256("continue preparing"),"EXPLICIT_CONTINUE","owner","session",
                    new IntentClassification(Intent.CONTINUE_CURRENT_THREAD,FollowUpClassifier.VERSION,"TEST"),AT.plusSeconds(1));
            CountDownLatch atBoundary=new CountDownLatch(1),release=new CountDownLatch(1);
            try(DatabaseManager racingManager=new DatabaseManager(workspace.resolve(".paicli/state"))){
                FollowUpStore racing=new FollowUpStore(racingManager.getConnection(),(point,candidate)->{
                    if("BEFORE_AUTHORITY_LOCK".equals(point)){atBoundary.countDown();
                        if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("test barrier timed out");}
                });
                ExecutorService executor=Executors.newSingleThreadExecutor();
                try{
                    Future<Candidate> attempt=executor.submit(()->racing.persistPrepared(preparing.capsuleId(),
                            preparing.version(),null,null,AT.plusSeconds(3)));
                    assertTrue(atBoundary.await(10,TimeUnit.SECONDS));
                    var winner=service(state).begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(2));
                    assertNotNull(winner.child());release.countDown();
                    assertEquals(CandidateState.HEAD_CONFLICT,attempt.get(10,TimeUnit.SECONDS).state());
                    assertEquals(0,count("SELECT count(*) FROM continuation_capsules WHERE capsule_id=?",
                            preparing.capsuleId().value()));
                }finally{release.countDown();executor.shutdownNow();}
            }
        }
    }

    @Test void riskCancelRaceUsesHeadPrecedenceWhileCommittedCancelRemainsTerminal()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var a=service.begin(seed.thread(),"ambiguous a",false,"owner","session",AT.plusSeconds(1));
            var b=service.begin(seed.thread(),"ambiguous b",false,"owner","session",AT.plusSeconds(1));
            Files.writeString(workspace.resolve("AGENTS.md"),"changed\n");
            a=service.confirm(a.candidate().capsuleId(),ConfirmationKind.INTENT,a.candidate().version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            b=service.confirm(b.candidate().capsuleId(),ConfirmationKind.INTENT,b.candidate().version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            Candidate cancelled=state.followUps.confirm(a.candidate().capsuleId(),ConfirmationKind.RISK,a.candidate().version(),
                    "owner","session",ConfirmationDecision.CANCEL,AT.plusSeconds(3));
            assertEquals(CandidateState.CANCELLED,cancelled.state());
            Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(4));
            assertNotNull(winner.child());
            Candidate conflict=state.followUps.confirm(b.candidate().capsuleId(),ConfirmationKind.RISK,b.candidate().version(),
                    "owner","session",ConfirmationDecision.CANCEL,AT.plusSeconds(5));
            assertEquals(CandidateState.HEAD_CONFLICT,conflict.state());
            assertEquals(CandidateState.CANCELLED,state.followUps.findCandidate(cancelled.capsuleId()).orElseThrow().state());
            assertEquals(0,count("SELECT count(*) FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind='RISK' AND decision='CANCEL'",
                    conflict.capsuleId().value()));
            assertEquals(CandidateState.HEAD_CONFLICT,state.followUps.failStaleManifest(conflict.capsuleId(),
                    conflict.version(),AT.plusSeconds(6)).state());
            assertEquals(CandidateState.HEAD_CONFLICT,state.followUps.confirm(conflict.capsuleId(),ConfirmationKind.RISK,
                    conflict.version(),"owner","session",ConfirmationDecision.CANCEL,AT.plusSeconds(6)).state());
            assertTrue(state.followUps.admitChild(conflict.capsuleId(),conflict.version(),"unused",runtimeSpec(),
                    AT.plusSeconds(6)) instanceof Rejected rejected&&"HEAD_CONFLICT".equals(rejected.code()));
        }
    }

    @Test void downstreamCorruptionLosesToHeadConflictButCoreCorruptionWritesNoFalseFact()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var downstream=service.begin(seed.thread(),"ambiguous downstream",false,"owner","session",AT.plusSeconds(1));
            var core=service.begin(seed.thread(),"ambiguous core",false,"owner","session",AT.plusSeconds(1));
            Files.writeString(workspace.resolve("AGENTS.md"),"changed\n");
            downstream=service.confirm(downstream.candidate().capsuleId(),ConfirmationKind.INTENT,downstream.candidate().version(),
                    "owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            Candidate downstreamReady=state.followUps.confirm(downstream.candidate().capsuleId(),ConfirmationKind.RISK,
                    downstream.candidate().version(),"owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(2));
            Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(3));assertNotNull(winner.child());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1e_capsule_immutable");
                sql.executeUpdate("UPDATE continuation_capsules SET canonical_hash='"+"0".repeat(64)+"' WHERE capsule_id='"
                        +downstream.candidate().capsuleId().value()+"'");
                sql.execute("DROP TRIGGER r1e_followup_definition_immutable");
                sql.executeUpdate("UPDATE followup_candidate_definitions SET instruction_hash='"+"0".repeat(64)+"' WHERE capsule_id='"
                        +core.candidate().capsuleId().value()+"'");
            }
            Candidate downstreamTerminal=state.followUps.failStaleManifest(downstreamReady.capsuleId(),
                    downstreamReady.version(),AT.plusSeconds(4));
            assertEquals(CandidateState.HEAD_CONFLICT,downstreamTerminal.state());
            assertThrows(RuntimeException.class,()->state.followUps.findPending(seed.thread()));
            assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=?",
                    core.candidate().capsuleId().value()));
        }
    }

    @Test void injectedHeadConflictFactApplyFailureRollsBackFactAndStateTogether()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            var stale=service.begin(seed.thread(),"ambiguous",false,"owner","session",AT.plusSeconds(1));
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(2));assertNotNull(winner.child());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("CREATE TRIGGER test_abort_head_conflict BEFORE UPDATE OF state ON followup_candidate_authority "
                        +"WHEN NEW.state='HEAD_CONFLICT' BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(SQLException.class,()->state.followUps.findPending(seed.thread()));
            assertEquals("NEEDS_INTENT_CONFIRMATION",text(
                    "SELECT state FROM followup_candidate_authority WHERE capsule_id=?",
                    stale.candidate().capsuleId().value()));
            assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=?",
                    stale.candidate().capsuleId().value()));
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("DROP TRIGGER test_abort_head_conflict");
            }
            assertTrue(state.followUps.findPending(seed.thread()).isEmpty());
            assertEquals(CandidateState.HEAD_CONFLICT,
                    state.followUps.findCandidate(stale.candidate().capsuleId()).orElseThrow().state());
            assertEquals(1,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='HEAD_CONFLICT'",
                    stale.candidate().capsuleId().value()));
        }
    }

    @Test void candidateCoreRequiresExactUnknownIntentProofAndValidatesItBeforeHead()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            Candidate unknown=service.begin(seed.thread(),"please inspect this",false,"owner","session",
                    AT.plusSeconds(1)).candidate();
            assertEquals(CandidateState.NEEDS_INTENT_CONFIRMATION,unknown.state());
            assertEquals(0,unknown.version());
            assertInvalidIntentConfirmation(unknown,0,"wrong-owner","session","CONTINUE_CURRENT_THREAD");
            assertInvalidIntentConfirmation(unknown,0,"owner","wrong-session","CONTINUE_CURRENT_THREAD");
            assertInvalidIntentConfirmation(unknown,1,"owner","session","CONTINUE_CURRENT_THREAD");
            assertInvalidIntentConfirmation(unknown,0,"owner","session","ACCEPT_RISK");
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
                    PreparedStatement update=m.getConnection().prepareStatement("""
                            UPDATE followup_candidate_authority SET state='PREPARING',candidate_version=1
                            WHERE capsule_id=?""")){
                update.setString(1,unknown.capsuleId().value());
                assertThrows(SQLException.class,update::executeUpdate);
            }
            Candidate preparing=state.followUps.confirm(unknown.capsuleId(),ConfirmationKind.INTENT,0,
                    "owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2));
            assertEquals(CandidateState.PREPARING,preparing.state());assertEquals(1,preparing.version());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                assertThrows(SQLException.class,()->sql.executeUpdate("INSERT INTO followup_confirmation_facts("
                        +"capsule_id,confirmation_kind,confirmation_number,expected_candidate_version,input_owner,"
                        +"input_session_id,decision,confirmed_at) VALUES('"+unknown.capsuleId().value()
                        +"','INTENT',2,0,'owner','session','CONTINUE_CURRENT_THREAD','"+AT+"')"));
            }
            ParentAnchor parent=state.followUps.currentEligibleParent(seed.thread()).orElseThrow();
            Candidate newTopic=state.followUps.createCandidate(parent,"new topic",
                    new FollowUpCanonicalizer().sha256("new topic"),"NATURAL_TERMINAL_INPUT","owner","session",
                    new IntentClassification(Intent.CREATE_NEW_THREAD,FollowUpClassifier.VERSION,"NEW_TOPIC"),
                    AT.plusSeconds(2));
            assertEquals(CandidateState.CANCELLED,newTopic.state());assertEquals(1,newTopic.version());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
                    PreparedStatement fact=m.getConnection().prepareStatement("""
                            SELECT source_state,source_candidate_version,resulting_candidate_version
                            FROM followup_admission_facts WHERE capsule_id=?""")){
                fact.setString(1,newTopic.capsuleId().value());try(ResultSet row=fact.executeQuery()){
                    assertTrue(row.next());assertEquals("CANDIDATE",row.getString(1));
                    assertEquals(0,row.getLong(2));assertEquals(1,row.getLong(3));
                }
            }
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(3));
            assertNotNull(winner.child());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1e_followup_confirmation_immutable");
                sql.executeUpdate("UPDATE followup_confirmation_facts SET input_owner='wrong-owner' WHERE capsule_id='"
                        +unknown.capsuleId().value()+"' AND confirmation_kind='INTENT'");
            }
            assertThrows(RuntimeException.class,()->state.followUps.findCandidate(unknown.capsuleId()));
            assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=?",
                    unknown.capsuleId().value()));
        }
    }

    @Test void preparedAggregateCorruptionBlocksRiskConfirmationAdmissionAndAdmittedReads()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            Candidate unknown=service.begin(seed.thread(),"please inspect this",false,"owner","session",
                    AT.plusSeconds(1)).candidate();
            Files.writeString(workspace.resolve("AGENTS.md"),"after\n");
            Candidate risk=service.confirm(unknown.capsuleId(),ConfirmationKind.INTENT,unknown.version(),
                    "owner","session",ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2)).candidate();
            assertEquals(CandidateState.NEEDS_RISK_CONFIRMATION,risk.state());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                assertThrows(SQLException.class,()->sql.executeUpdate("UPDATE followup_candidate_authority SET risk='LOW' WHERE capsule_id='"
                        +risk.capsuleId().value()+"'"));
                sql.execute("DROP TRIGGER r1e_manifest_diff_immutable");
                String duplicate="INSERT INTO manifest_diff_facts(capsule_id,diff_number,category,source_path,"
                        +"parent_hash,child_hash,bounded_summary,provenance) SELECT capsule_id,999,category,"
                        +"source_path,parent_hash,child_hash,'duplicate',provenance FROM manifest_diff_facts "
                        +"WHERE capsule_id='"+risk.capsuleId().value()+"' LIMIT 1";
                assertThrows(SQLException.class,()->sql.executeUpdate(duplicate));
                sql.executeUpdate("UPDATE manifest_diff_facts SET category='UNCHANGED' WHERE capsule_id='"
                        +risk.capsuleId().value()+"' AND diff_number=1");
                sql.executeUpdate("INSERT INTO manifest_diff_facts(capsule_id,diff_number,category,source_path,"
                        +"bounded_summary,provenance) VALUES('"+risk.capsuleId().value()
                        +"',999,'ADDED','EXTRA.md','extra','PARENT_COMMITTED_VS_CHILD_FRESH')");
            }
            assertThrows(RuntimeException.class,()->state.followUps.confirm(risk.capsuleId(),ConfirmationKind.RISK,
                    risk.version(),"owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3)));
            assertEquals(0,count("SELECT count(*) FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind='RISK'",
                    risk.capsuleId().value()));

            Candidate second=service.begin(seed.thread(),"continue second",true,"owner","session",AT.plusSeconds(4)).candidate();
            Candidate ready=state.followUps.confirm(second.capsuleId(),ConfirmationKind.RISK,second.version(),
                    "owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(5));
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1e_manifest_diff_no_delete");
                sql.executeUpdate("DELETE FROM manifest_diff_facts WHERE capsule_id='"
                        +ready.capsuleId().value()+"' AND diff_number=(SELECT min(diff_number) FROM manifest_diff_facts WHERE capsule_id='"
                        +ready.capsuleId().value()+"')");
            }
            AdmissionResult rejected=state.followUps.admitChild(ready.capsuleId(),ready.version(),
                    text("SELECT source_fingerprint FROM child_manifest_candidates WHERE capsule_id=?",ready.capsuleId().value()),
                    runtimeSpec(),AT.plusSeconds(6));
            assertTrue(rejected instanceof Rejected);
            assertEquals(0,count("SELECT count(*) FROM task_identities WHERE parent_task_id=?",seed.task().value()));

            Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
            var admitted=service.begin(seed.thread(),"clean child",true,"owner","session",AT.plusSeconds(7));
            assertNotNull(admitted.child());
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1e_child_manifest_candidate_immutable");
                sql.executeUpdate("UPDATE child_manifest_candidates SET canonical_hash='"+"0".repeat(64)
                        +"' WHERE capsule_id='"+admitted.candidate().capsuleId().value()+"'");
            }
            assertThrows(RuntimeException.class,()->state.followUps.findAdmittedChild(admitted.candidate().capsuleId()));
            assertFalse(state.followUps.childOriginIntegrityValid(admitted.child().taskId()));
        }
    }

    @Test void preparedValidatorRecomputesRiskAndProtectedArtifactIntegrity()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"before\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            Candidate forged=service.begin(seed.thread(),"please inspect this",false,"owner","session",
                    AT.plusSeconds(1)).candidate();
            Files.writeString(workspace.resolve("AGENTS.md"),"after\n");
            forged=service.confirm(forged.capsuleId(),ConfirmationKind.INTENT,forged.version(),"owner","session",
                    ConfirmationDecision.CONTINUE_CURRENT_THREAD,AT.plusSeconds(2)).candidate();
            assertEquals(CandidateState.NEEDS_RISK_CONFIRMATION,forged.state());
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    Statement sql=manager.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1e_risk_immutable");
                sql.executeUpdate("UPDATE followup_risk_classification_facts SET risk='LOW' WHERE capsule_id='"
                        +forged.capsuleId().value()+"'");
            }
            Candidate forgedRisk=forged;
            assertThrows(RuntimeException.class,()->state.followUps.confirm(forgedRisk.capsuleId(),ConfirmationKind.RISK,
                    forgedRisk.version(),"owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(3)));
            assertEquals(0,count("SELECT count(*) FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind='RISK'",
                    forged.capsuleId().value()));

            Candidate artifact=service.begin(seed.thread(),"continue artifact",true,"owner","session",
                    AT.plusSeconds(4)).candidate();
            assertEquals(CandidateState.NEEDS_RISK_CONFIRMATION,artifact.state());
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    Statement sql=manager.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1a_artifact_immutable");
                sql.executeUpdate("UPDATE protected_instruction_artifacts SET protected_payload=x'00' WHERE artifact_id=("
                        +"SELECT artifact_id FROM child_manifest_candidate_entries WHERE capsule_id='"
                        +artifact.capsuleId().value()+"' AND status='APPLIED' LIMIT 1)");
            }
            Candidate corruptArtifact=artifact;
            assertThrows(RuntimeException.class,()->state.followUps.confirm(corruptArtifact.capsuleId(),ConfirmationKind.RISK,
                    corruptArtifact.version(),"owner","session",ConfirmationDecision.ACCEPT_RISK,AT.plusSeconds(5)));
            assertEquals(0,count("SELECT count(*) FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind='RISK'",
                    artifact.capsuleId().value()));
        }
    }

    @Test void pendingScanCommitsMultipleStaleCandidatesThenReturnsNextCurrentHeadCandidateInStableOrder()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            Candidate a=service.begin(seed.thread(),"ambiguous a",false,"owner","session",AT.plusSeconds(1)).candidate();
            Candidate b=service.begin(seed.thread(),"ambiguous b",false,"owner","session",AT.plusSeconds(2)).candidate();
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(3));
            terminal(winner.child().taskId(),winner.child().runId(),"COMPLETED");
            Candidate c=service.begin(seed.thread(),"ambiguous current",false,"owner","session",AT.plusSeconds(4)).candidate();
            CopyOnWriteArrayList<CanonicalId> order=new CopyOnWriteArrayList<>();
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"))){
                FollowUpStore scanner=new FollowUpStore(manager.getConnection(),(point,candidate)->{
                    if("BEFORE_HEAD_LOCK".equals(point)&&candidate!=null)order.add(candidate.capsuleId());
                });
                Candidate pending=scanner.findPending(seed.thread()).orElseThrow();
                assertEquals(c.capsuleId(),pending.capsuleId());
            }
            assertEquals(java.util.List.of(a.capsuleId(),b.capsuleId(),c.capsuleId()),order);
            assertEquals(1,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='HEAD_CONFLICT'",
                    a.capsuleId().value()));
            assertEquals(1,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='HEAD_CONFLICT'",
                    b.capsuleId().value()));
            assertEquals(CandidateState.NEEDS_INTENT_CONFIRMATION,
                    state.followUps.findCandidate(c.capsuleId()).orElseThrow().state());
        }
    }

    @Test void pendingScanStopsAtCurrentHeadCoreBlockerAfterCommittingEarlierStaleCandidate()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            Candidate stale=service.begin(seed.thread(),"ambiguous stale",false,"owner","session",AT.plusSeconds(1)).candidate();
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(2));
            terminal(winner.child().taskId(),winner.child().runId(),"COMPLETED");
            Candidate blocker=service.begin(seed.thread(),"ambiguous blocker",false,"owner","session",AT.plusSeconds(3)).candidate();
            Candidate later=service.begin(seed.thread(),"ambiguous later",false,"owner","session",AT.plusSeconds(4)).candidate();
            try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));Statement sql=m.getConnection().createStatement()){
                sql.execute("DROP TRIGGER r1e_followup_definition_immutable");
                sql.executeUpdate("UPDATE followup_candidate_definitions SET instruction_hash='"+"0".repeat(64)
                        +"' WHERE capsule_id='"+blocker.capsuleId().value()+"'");
            }
            assertThrows(RuntimeException.class,()->state.followUps.findPending(seed.thread()));
            assertEquals(1,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='HEAD_CONFLICT'",
                    stale.capsuleId().value()));
            assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=?",
                    later.capsuleId().value()));
        }
    }

    @Test void pendingScanStopsAtCurrentHeadCompletionBlockerAfterCommittingEarlierStaleCandidate()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=completedParent(state,"goal");FollowUpService service=service(state);
            Candidate stale=service.begin(seed.thread(),"ambiguous stale",false,"owner","session",
                    AT.plusSeconds(1)).candidate();
            var winner=service.begin(seed.thread(),"winning child",true,"owner","session",AT.plusSeconds(2));
            terminal(winner.child().taskId(),winner.child().runId(),"COMPLETED");
            Candidate blocker=service.begin(seed.thread(),"ambiguous blocker",false,"owner","session",
                    AT.plusSeconds(3)).candidate();
            Candidate later=service.begin(seed.thread(),"ambiguous later",false,"owner","session",
                    AT.plusSeconds(4)).candidate();
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    PreparedStatement damage=manager.getConnection().prepareStatement(
                            "UPDATE durable_runs SET terminal_status='FAILED' WHERE run_id=?")){
                damage.setString(1,winner.child().runId().value());assertEquals(1,damage.executeUpdate());
            }
            assertThrows(RuntimeException.class,()->state.followUps.findPending(seed.thread()));
            assertEquals(1,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=? AND result='HEAD_CONFLICT'",
                    stale.capsuleId().value()));
            assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=?",
                    blocker.capsuleId().value()));
            assertEquals(0,count("SELECT count(*) FROM followup_admission_facts WHERE capsule_id=?",
                    later.capsuleId().value()));
        }
    }

    private Seed completedParent(StateStore state,String goal)throws Exception{
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);var root=new InstructionManifestRuntime(state,workspace)
                .admitRoot(task,goal,10_000,config(),RuntimeManifest.disabled(),AT);terminal(task,root.initialRun().id(),"COMPLETED");
        return new Seed(root.threadId(),task);
    }
    private void terminal(CanonicalId task,CanonicalId run,String status)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));Statement s=manager.getConnection().createStatement()){
            s.executeUpdate("UPDATE task_identities SET status='"+status+"',terminal_at='"+AT+"' WHERE task_id='"+task.value()+"'");
            s.executeUpdate("UPDATE durable_runs SET terminal_status='"+status+"' WHERE run_id='"+run.value()+"'");
            s.executeUpdate("UPDATE tasks SET status='"+("COMPLETED".equals(status)?"DONE":"FAILED")+"',completed_at='"+AT+"' WHERE task_id='"+task.value()+"'");
            s.executeUpdate("UPDATE coding_runs SET phase='"+("COMPLETED".equals(status)?"COMPLETED":"FAILED")+"',completed_at='"+AT+"' WHERE run_id='"+run.value()+"'");}
    }
    private FollowUpService service(StateStore state){return service(state,config(false));}
    private void assertInvalidIntentConfirmation(Candidate candidate,long expectedVersion,String owner,String session,
            String decision)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                PreparedStatement insert=manager.getConnection().prepareStatement("""
                        INSERT INTO followup_confirmation_facts(capsule_id,confirmation_kind,confirmation_number,
                            expected_candidate_version,input_owner,input_session_id,decision,confirmed_at)
                        VALUES(?,'INTENT',1,?,?,?,?,?)""")){
            insert.setString(1,candidate.capsuleId().value());insert.setLong(2,expectedVersion);
            insert.setString(3,owner);insert.setString(4,session);insert.setString(5,decision);
            insert.setString(6,AT.toString());assertThrows(SQLException.class,insert::executeUpdate);
        }
    }
    private FollowUpService service(StateStore state,Config config){return new FollowUpService(state,
            new InstructionManifestRuntime(state,workspace),config,RuntimeManifest.disabled(),10_000);}
    private Config config(){return config(false);}
    private Config config(boolean plan){return new Config(workspace.toString(),30,50_000,200_000,Config.MODE_WORKSPACE_WRITE,10_000,
            Config.PROVIDER_MOCK,"model","","","system",10,"",120,50_000,1,plan,false,false);}
    private FollowUpStore.ChildRuntimeSpec runtimeSpec(){return new FollowUpStore.ChildRuntimeSpec("child",Config.PROVIDER_MOCK,"model",
            Config.MODE_WORKSPACE_WRITE,10,"",1,null,null,"{}",StructuredPlanModels.PlanApplicability.PLAN_DISABLED);}
    private int count(String sql,String value)throws Exception{try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
            PreparedStatement s=m.getConnection().prepareStatement(sql)){s.setString(1,value);try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getInt(1);}}}
    private int count(String sql,String first,String second)throws Exception{try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
            PreparedStatement s=m.getConnection().prepareStatement(sql)){s.setString(1,first);s.setString(2,second);try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getInt(1);}}}
    private String text(String sql,String value)throws Exception{try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
            PreparedStatement s=m.getConnection().prepareStatement(sql)){s.setString(1,value);try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
    private CanonicalId head(CanonicalId thread)throws Exception{return queryId("SELECT head_task_id FROM thread_heads WHERE thread_id=?",thread.value());}
    private CanonicalId boundParent(CanonicalId capsule)throws Exception{return queryId("SELECT parent_task_id FROM capsule_child_bindings WHERE capsule_id=?",capsule.value());}
    private CanonicalId queryId(String sql,String value)throws Exception{try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
            PreparedStatement s=m.getConnection().prepareStatement(sql)){s.setString(1,value);try(ResultSet r=s.executeQuery()){assertTrue(r.next());return CanonicalId.parse(r.getString(1));}}}
    private ParentRow parentRow(CanonicalId id)throws Exception{try(DatabaseManager m=new DatabaseManager(workspace.resolve(".paicli/state"));
            PreparedStatement s=m.getConnection().prepareStatement("SELECT goal,status,terminal_at,admitted_manifest_id,admitted_manifest_hash FROM task_identities WHERE task_id=?")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){assertTrue(r.next());return new ParentRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5));}}}
    private record Seed(CanonicalId thread,CanonicalId task){ }
    private record ParentRow(String goal,String status,String terminalAt,String manifestId,String manifestHash){ }
}
