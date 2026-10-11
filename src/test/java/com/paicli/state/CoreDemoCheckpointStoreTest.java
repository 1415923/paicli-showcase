package com.paicli.state;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoCheckpointStoreTest {
    @TempDir Path root;
    @Test void immutableCheckpointIsIdempotentAndDriftFailsClosed() throws Exception {
        try(StateStore store=new StateStore(root)){
            String run="run-checkpoint-01",task="task-checkpoint-01";
            store.coreDemoRuns.create(new CoreDemoRunStore.CreateRun(run,"scenario","1",
                    "a".repeat(64),"b".repeat(64),"c".repeat(64),"provider"));
            store.coreDemoRuns.recordBaselinePassed(run,3,"b".repeat(64));
            store.tasks.createTask(task,"goal","provider","model","workspace-write",8);
            store.tasks.markRunning(task);store.coreDemoRuns.startWorkflow(run);
            store.coreDemoRuns.bindWorkflowIdentity(run,task,"primary-occurrence");
            var value=new CoreDemoCheckpointStore.CreateCheckpoint("checkpoint-0001",run,task,
                    "primary-occurrence","d".repeat(64),"e".repeat(64),"f".repeat(64),
                    "1".repeat(64),List.of("src/main/PricingService.java"),
                    List.of("snapshot-0001"),"provider-v1","approval-v1",
                    "2".repeat(64),"3".repeat(64));
            assertEquals(store.coreDemoCheckpoints.saveOrFind(value),
                    store.coreDemoCheckpoints.saveOrFind(value));
            var drift=new CoreDemoCheckpointStore.CreateCheckpoint("checkpoint-9999",run,task,
                    "primary-occurrence","d".repeat(64),"9".repeat(64),"f".repeat(64),
                    "1".repeat(64),value.changedFiles(),value.snapshotIds(),
                    "provider-v1","approval-v1","2".repeat(64),"3".repeat(64));
            assertThrows(IllegalStateException.class,
                    ()->store.coreDemoCheckpoints.saveOrFind(drift));
        }
    }
}