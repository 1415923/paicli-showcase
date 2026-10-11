package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoRunStoreTest {
    private static final String SHA="a".repeat(64);
    @TempDir Path root;

    @Test void lifecycleRequiresBaselineAndArtifactCommit() throws Exception {
        Path run=root.resolve("run-00000001");Files.createDirectories(run.resolve("state"));
        try(StateStore state=StateStore.openCoreDemo(run.resolve("state/paicli.db"),run)){
            var store=state.coreDemoRuns;
            store.create(new CoreDemoRunStore.CreateRun("run-00000001","scenario","1",
                    SHA,SHA,SHA,"DETERMINISTIC_SCRIPTED"));
            assertThrows(IllegalStateException.class,()->store.startWorkflow("run-00000001"));
            store.recordPreparationPhase("run-00000001","EXTRACTION");
            store.recordPreparationPhase("run-00000001","BASELINE");
            store.recordBaselinePassed("run-00000001",3,SHA);
            store.startWorkflow("run-00000001");
            store.completeWorkflow("run-00000001",true,"VERIFY",SHA,1,false,false,null,null);
            assertThrows(IllegalStateException.class,()->store.markCleaned("run-00000001"));
            store.startArtifacts("run-00000001");
            assertEquals("STAGING",store.findById("run-00000001").orElseThrow().artifactStatus());
            store.commitArtifacts("run-00000001",0);
            assertEquals(CoreDemoRunStore.COMPLETED,store.findById("run-00000001").orElseThrow().status());
            assertThrows(IllegalStateException.class,()->store.completeWorkflow(
                    "run-00000001",false,"VERIFY",null,0,false,false,"LATE","late"));
            store.markCleanupPending("run-00000001");store.markCleaned("run-00000001");
            assertEquals(CoreDemoRunStore.CLEANED,store.findById("run-00000001").orElseThrow().status());
        }
    }

    @Test void baselineFailureCannotEnterWorkflowAndErrorIsBoundedRedacted() throws Exception {
        Path run=root.resolve("run-00000002");Files.createDirectories(run.resolve("state"));
        try(StateStore state=StateStore.openCoreDemo(run.resolve("state/paicli.db"),run)){
            var store=state.coreDemoRuns;
            store.create(new CoreDemoRunStore.CreateRun("run-00000002","scenario","1",
                    SHA,SHA,SHA,"DETERMINISTIC_SCRIPTED"));
            store.recordBaselineFailed("run-00000002",0,"BASELINE",
                    "Authorization: Bearer sentinel C:\\Users\\private\\file "+"x".repeat(6000));
            var row=store.findById("run-00000002").orElseThrow();
            assertEquals(CoreDemoRunStore.FAILED,row.status());
            assertTrue(row.boundedErrorExcerpt().length()<=4096);
            assertFalse(row.boundedErrorExcerpt().contains("sentinel"));
            assertFalse(row.boundedErrorExcerpt().contains("C:\\Users\\private"));
            assertThrows(IllegalStateException.class,()->store.startWorkflow("run-00000002"));
            store.startArtifacts("run-00000002");store.failArtifacts("run-00000002","WRITE","failed");
            var retained=store.findById("run-00000002").orElseThrow();
            assertEquals(CoreDemoRunStore.RETAINED_FOR_DIAGNOSTICS,retained.status());
            assertEquals("FAILED",retained.artifactStatus());
        }
    }

    @Test void demoStoreLocationIsExactAndCloseInvalidatesStore() throws Exception {
        Path run=root.resolve("run-00000003");Files.createDirectories(run.resolve("state"));
        assertThrows(IllegalArgumentException.class,()->StateStore.openCoreDemo(
                run.resolve("other/paicli.db"),run));
        StateStore state=StateStore.openCoreDemo(run.resolve("state/paicli.db"),run);
        state.close();
        assertThrows(java.sql.SQLException.class,()->state.coreDemoRuns.findById("run-00000003"));
    }

    @Test void resumeAndRewindOwnersAreCasBoundAndTerminal() throws Exception {
        Path run=root.resolve("run-00000004");Files.createDirectories(run.resolve("state"));
        try(StateStore state=StateStore.openCoreDemo(run.resolve("state/paicli.db"),run)){
            var store=state.coreDemoRuns;
            store.create(new CoreDemoRunStore.CreateRun("run-00000004","scenario","1",
                    SHA,SHA,SHA,"DETERMINISTIC_SCRIPTED"));
            store.recordBaselinePassed("run-00000004",3,SHA);
            store.startWorkflow("run-00000004");
            store.recordWorkflowPhase("run-00000004","PLAN","CODE_INTELLIGENCE");
            store.recordWorkflowPhase("run-00000004","CODE_INTELLIGENCE","EXECUTE");
            store.recordWorkflowPhase("run-00000004","EXECUTE","VERIFY");
            store.markPrimaryInterruptedAtCheckpoint("run-00000004");
            store.beginResume("run-00000004","resume-owner-0001");
            assertThrows(IllegalStateException.class,
                    ()->store.beginResume("run-00000004","resume-owner-0002"));
            store.markResumeRunning("run-00000004","resume-owner-0001");
            store.markResumeSucceeded("run-00000004","resume-owner-0001",SHA,1);
            store.beginRewind("run-00000004","rewind-owner-001");
            assertThrows(IllegalStateException.class,
                    ()->store.beginRewind("run-00000004","rewind-owner-002"));
            store.markRewindSucceeded("run-00000004","rewind-owner-001",SHA);
            var row=store.findById("run-00000004").orElseThrow();
            assertEquals(CoreDemoRunStore.RESUME_SUCCEEDED,row.resumeStatus());
            assertEquals(CoreDemoRunStore.REWIND_SUCCEEDED,row.rewindStatus());
            assertTrue(row.resumed());assertTrue(row.rewound());
        }
    }
}
