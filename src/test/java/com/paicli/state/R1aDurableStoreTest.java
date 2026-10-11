package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.R1aStoreResults.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class R1aDurableStoreTest {
    @TempDir Path tempDir;
    private static final String HASH = "a".repeat(64);

    @Test void threadGenerationConstraintAndManifestFoundationsUseTypedCasResults() throws Exception {
        try (DatabaseManager manager=new DatabaseManager(tempDir.resolve("core"))) {
            new MigrationRunner(manager.getConnection()).runMigrations();
            R1aDurableStore store=new R1aDurableStore(manager.getConnection()); Instant at=Instant.parse("2026-07-16T00:00:00Z");
            WorkThread thread=thread(at); DurableTask task=task(thread.id(),null,at);
            assertEquals(CreateResult.SUCCESS,store.createThread(thread)); assertEquals(CreateResult.SUCCESS,store.createTask(task));
            InstructionManifest manifest=commitManifest(store,task.id(),at);
            DurableRun run=run(task,manifest,null,at); assertEquals(CreateResult.SUCCESS,store.createRun(run));
            TaskContextGeneration generation=new TaskContextGeneration(CanonicalId.newId(EntityFamily.GENERATION),task.id(),null,run.id(),manifest.id(),manifest.canonicalHash(),"COMMITTED","{}",at,at);
            assertEquals(CreateResult.SUCCESS,store.createGeneration(generation));
            assertEquals(CreateResult.SUCCESS,store.createContextPointer(new TaskContextPointer(task.id(),generation.id(),run.id(),0)));
            assertEquals(PointerCasResult.VERSION_CONFLICT,store.compareAndSetContextPointer(task.id(),generation.id(),run.id(),1,generation.id(),run.id()));
            assertEquals(PointerCasResult.SUCCESS,store.compareAndSetContextPointer(task.id(),generation.id(),run.id(),0,generation.id(),run.id()));
            assertEquals(PointerCasResult.EXPECTATION_MISMATCH,
                    store.compareAndSetContextPointer(task.id(),
                            CanonicalId.newId(EntityFamily.GENERATION), run.id(), 1,
                            generation.id(), run.id()));
            ConstraintRevision first=new ConstraintRevision("constraint-rev-0001",task.id(),1,"one",HASH,at);
            ConstraintRevision second=new ConstraintRevision("constraint-rev-0002",task.id(),2,"two","b".repeat(64),at.plusSeconds(1));
            assertEquals(CreateResult.SUCCESS,store.appendConstraintRevision(first)); assertEquals(CreateResult.SUCCESS,store.appendConstraintRevision(second));
            assertEquals(CreateResult.SUCCESS,store.createConstraintActivation(new ConstraintActivation(task.id(),first.id(),run.id(),0,at)));
            assertEquals(PointerCasResult.VERSION_CONFLICT,store.compareAndSetConstraintActivation(task.id(),first.id(),1,second.id(),run.id(),at.plusSeconds(2)));
            assertEquals(PointerCasResult.SUCCESS,store.compareAndSetConstraintActivation(task.id(),first.id(),0,second.id(),run.id(),at.plusSeconds(2)));
            assertEquals(PointerCasResult.VERSION_CONFLICT,store.compareAndSetThreadHead(thread.id(),task.id(),1,task.id()));
        }
    }

    @Test void occurrenceAdmissionIsIdempotentScopedAndTerminalStateCannotRegress() throws Exception {
        try (DatabaseManager manager=new DatabaseManager(tempDir.resolve("occurrence"))) {
            new MigrationRunner(manager.getConnection()).runMigrations(); R1aDurableStore store=new R1aDurableStore(manager.getConnection()); Instant at=Instant.parse("2026-07-16T00:00:00Z");
            DurableTask first=admittedTask(store,at); InstructionManifest firstManifest=store.findManifestByTask(first.id()).orElseThrow(); DurableRun firstRun=run(first,firstManifest,null,at); store.createRun(firstRun);
            DurableTask second=admittedTask(store,at.plusSeconds(1)); InstructionManifest secondManifest=store.findManifestByTask(second.id()).orElseThrow(); DurableRun secondRun=run(second,secondManifest,null,at); store.createRun(secondRun);
            SideEffectOccurrence started=occurrence(first,firstRun,"same-key","STARTED",at);
            assertEquals(OccurrenceAdmissionResult.SUCCESS,store.admitOccurrence(started));
            assertEquals(OccurrenceAdmissionResult.ALREADY_EXISTS,store.admitOccurrence(occurrence(first,firstRun,"same-key","STARTED",at)));
            assertEquals(OccurrenceAdmissionResult.SUCCESS,store.admitOccurrence(occurrence(second,secondRun,"same-key","STARTED",at)));
            assertTrue(store.findRecoveryBlockingOccurrences(first.id(),firstRun.id(),"WRITE").stream().anyMatch(o->o.status().equals("STARTED")));
            assertEquals(OccurrenceTransitionResult.SUCCESS,store.completeOccurrence(started.id(),"UNKNOWN",null,"unresolved",at.plusSeconds(1)));
            assertEquals(OccurrenceTransitionResult.INVALID_STATE,store.completeOccurrence(started.id(),"COMPLETED",HASH,"late",at.plusSeconds(2)));
            assertTrue(store.findRecoveryBlockingOccurrences(first.id(),firstRun.id(),"WRITE").stream().anyMatch(o->o.status().equals("UNKNOWN")));
        }
    }

    @Test void ownershipRequiresExplicitExpiryTakeoverAndRejectsOldFence() throws Exception {
        try (DatabaseManager manager=new DatabaseManager(tempDir.resolve("ownership"))) {
            new MigrationRunner(manager.getConnection()).runMigrations(); R1aDurableStore store=new R1aDurableStore(manager.getConnection()); Instant at=Instant.parse("2026-07-16T00:00:00Z");
            DurableTask task=admittedTask(store,at); InstructionManifest manifest=store.findManifestByTask(task.id()).orElseThrow(); DurableRun initial=run(task,manifest,null,at); store.createRun(initial);
            assertEquals(OwnershipResult.SUCCESS,store.acquireOwnership(task.id(),"instance-a",initial.id(),"lease-a",at,at.plusSeconds(10)));
            assertEquals(OwnershipResult.OWNERSHIP_CONFLICT,store.acquireOwnership(task.id(),"instance-b",initial.id(),"lease-b",at.plusSeconds(1),at.plusSeconds(11)));
            assertEquals(OwnershipResult.SUCCESS,store.heartbeatOwnership(task.id(),"instance-a",initial.id(),"lease-a",1,at.plusSeconds(2),at.plusSeconds(12)));
            assertEquals(OwnershipResult.SUCCESS,store.validateExecutionAuthority(task.id(),
                    "instance-a",initial.id(),"lease-a",1,2,at.plusSeconds(3)));
            assertEquals(OwnershipResult.VERSION_CONFLICT,store.validateExecutionAuthority(task.id(),
                    "instance-a",initial.id(),"lease-a",1,1,at.plusSeconds(3)));
            DurableRun resume=run(task,manifest,initial.id(),at.plusSeconds(20)); store.createRun(resume);
            assertEquals(OwnershipResult.OWNERSHIP_CONFLICT,store.takeoverOwnership(task.id(),resume.id(),"instance-b","lease-b",2,at.plusSeconds(12),at.plusSeconds(22),Duration.ofSeconds(1)));
            assertEquals(OwnershipResult.SUCCESS,store.takeoverOwnership(task.id(),resume.id(),"instance-b","lease-b",2,at.plusSeconds(14),at.plusSeconds(24),Duration.ofSeconds(1)));
            assertEquals(OwnershipResult.FENCING_REJECTED,store.validateFencing(task.id(),"lease-a",1,at.plusSeconds(15)));
            assertEquals(OwnershipResult.SUCCESS,store.validateFencing(task.id(),"lease-b",2,at.plusSeconds(15)));
        }
    }

    @Test void manifestCommitVerifiesArtifactsAndLeavesNoHalfCommitOnRejection() throws Exception {
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("manifest"))) {
            new MigrationRunner(manager.getConnection()).runMigrations(); R1aDurableStore store=new R1aDurableStore(manager.getConnection()); Instant at=Instant.parse("2026-07-16T00:00:00Z");
            WorkThread thread=thread(at); store.createThread(thread); DurableTask task=task(thread.id(),null,at); store.createTask(task);
            ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.inlineUtf8("artifact-00000001","content","v1","task",at);
            var entry=new InstructionManifestCandidate.Entry("entry-0000000001","AGENTS","AGENTS.md","workspace",InstructionManifestCandidate.Status.APPLIED,0,"f".repeat(64),artifact.id(),"OK",null,null);
            ManifestCommitResult rejected=store.commitManifest(task.id(),"manifest-0000001",new InstructionManifestCandidate(1,"v1",List.of(entry)),List.of(artifact),at);
            assertEquals(ManifestCommitResult.Reason.INTEGRITY_VIOLATION,((ManifestCommitResult.Rejected)rejected).reason());
            assertTrue(store.findManifestByTask(task.id()).isEmpty());
            ManifestCommitResult.Success committed = assertInstanceOf(
                    ManifestCommitResult.Success.class, commitManifestResult(store,task.id(),at));
            String committedArtifactId = committed.manifest().entries().get(0).artifactId();
            assertTrue(store.findInlineArtifact(committedArtifactId).orElseThrow().verifyIntegrity());
            assertEquals(List.of(0),store.findManifestByTask(task.id()).orElseThrow().entries().stream().filter(e->e.appliedOrder()!=null).map(InstructionManifest.InstructionEntry::appliedOrder).toList());
        }
    }

    @Test void manifestCommitRollsBackArtifactsAfterMidTransactionCollision() throws Exception {
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("manifest-rollback"))) {
            new MigrationRunner(manager.getConnection()).runMigrations();
            R1aDurableStore store=new R1aDurableStore(manager.getConnection());
            Instant at=Instant.parse("2026-07-16T00:00:00Z");
            WorkThread firstThread=thread(at); store.createThread(firstThread);
            DurableTask firstTask=task(firstThread.id(),null,at); store.createTask(firstTask);
            ProtectedInstructionArtifact shared=ProtectedInstructionArtifact.inlineUtf8(
                    "artifact-shared-0001","original","v1","task",at);
            var firstEntry=new InstructionManifestCandidate.Entry("entry-shared-000001",
                    "AGENTS","AGENTS.md","workspace",
                    InstructionManifestCandidate.Status.APPLIED,0,shared.contentHash(),
                    shared.id(),"OK",null,null);
            assertInstanceOf(ManifestCommitResult.Success.class,
                    store.commitManifest(firstTask.id(),"manifest-shared-001",
                            new InstructionManifestCandidate(1,"v1",List.of(firstEntry)),
                            List.of(shared),at));

            WorkThread secondThread=thread(at); store.createThread(secondThread);
            DurableTask secondTask=task(secondThread.id(),null,at); store.createTask(secondTask);
            ProtectedInstructionArtifact newArtifact=ProtectedInstructionArtifact.inlineUtf8(
                    "artifact-new-0000001","new","v1","task",at);
            ProtectedInstructionArtifact collision=ProtectedInstructionArtifact.inlineUtf8(
                    shared.id(),"different","v1","task",at);
            var newEntry=new InstructionManifestCandidate.Entry("entry-new-000000001",
                    "AGENTS","AGENTS.md","workspace",
                    InstructionManifestCandidate.Status.APPLIED,0,newArtifact.contentHash(),
                    newArtifact.id(),"OK",null,null);
            var collisionEntry=new InstructionManifestCandidate.Entry("entry-collision-0001",
                    "PAICLI","PAICLI.md","workspace",
                    InstructionManifestCandidate.Status.APPLIED,1,collision.contentHash(),
                    collision.id(),"OK",null,null);
            assertThrows(java.sql.SQLException.class,
                    () -> store.commitManifest(secondTask.id(),"manifest-rollback-01",
                            new InstructionManifestCandidate(1,"v1",
                                    List.of(newEntry,collisionEntry)),
                            List.of(newArtifact,collision),at));
            assertTrue(store.findInlineArtifact(newArtifact.id()).isEmpty());
            assertTrue(store.findManifestByTask(secondTask.id()).isEmpty());
        }
    }

    private static DurableTask admittedTask(R1aDurableStore store,Instant at)throws Exception{WorkThread thread=thread(at);store.createThread(thread);DurableTask task=task(thread.id(),null,at);store.createTask(task);commitManifest(store,task.id(),at);return task;}
    private static WorkThread thread(Instant at){return new WorkThread(CanonicalId.newId(EntityFamily.THREAD),"workspace-"+CanonicalId.newId(EntityFamily.TASK).shortDisplay(),"goal","OPEN",at,at);}
    private static DurableTask task(CanonicalId threadId,CanonicalId parent,Instant at){return new DurableTask(CanonicalId.newId(EntityFamily.TASK),threadId,parent,"goal","CREATED",null,null,at,null);}
    private static DurableRun run(DurableTask task,InstructionManifest manifest,CanonicalId resumed,Instant at){return new DurableRun(CanonicalId.newId(EntityFamily.RUN),task.id(),task.threadId(),resumed==null?"INITIAL":"RESUME",resumed,manifest.id(),manifest.canonicalHash(),at,null);}
    private static SideEffectOccurrence occurrence(DurableTask task,DurableRun run,String key,String status,Instant at){return new SideEffectOccurrence(CanonicalId.newId(EntityFamily.OCCURRENCE),task.threadId(),task.id(),run.id(),"iteration-1",key,"call-1","WRITE","file",status,HASH,null,null,at,null);}
    private static ManifestCommitResult commitManifestResult(R1aDurableStore store,CanonicalId taskId,Instant at)throws Exception{String suffix=taskId.value().substring(4);ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.inlineUtf8("artifact-"+suffix,"content","v1","task",at);var entry=new InstructionManifestCandidate.Entry("entry-"+suffix,"AGENTS","AGENTS.md","workspace",InstructionManifestCandidate.Status.APPLIED,0,artifact.contentHash(),artifact.id(),"OK",null,null);return store.commitManifest(taskId,"manifest-"+suffix,new InstructionManifestCandidate(1,"v1",List.of(entry)),List.of(artifact),at);}
    private static InstructionManifest commitManifest(R1aDurableStore store,CanonicalId taskId,Instant at)throws Exception{return ((ManifestCommitResult.Success)commitManifestResult(store,taskId,at)).manifest();}
}
