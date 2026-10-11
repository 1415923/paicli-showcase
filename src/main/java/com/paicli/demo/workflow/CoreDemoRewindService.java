package com.paicli.demo.workflow;

import com.paicli.demo.baseline.*;
import com.paicli.demo.fixture.CoreDemoTreeHasher;
import com.paicli.demo.runtime.CoreDemoRunContext;
import com.paicli.demo.scenario.*;
import com.paicli.state.*;

import java.time.Duration;
import java.util.*;

public final class CoreDemoRewindService {
    private final CoreDemoScenarioContractLoader contracts;
    private final CoreDemoBaselineService baseline;

    public CoreDemoRewindService() {
        this(new CoreDemoScenarioContractLoader(),
                new CoreDemoBaselineService(Duration.ofSeconds(90)));
    }
    CoreDemoRewindService(CoreDemoScenarioContractLoader contracts,
            CoreDemoBaselineService baseline) {
        this.contracts=Objects.requireNonNull(contracts);
        this.baseline=Objects.requireNonNull(baseline);
    }

    public CoreDemoRewindResult rewind(CoreDemoRunContext context,
            CoreDemoResumeResult resume) throws Exception {
        CoreDemoScenarioContract c=contracts.load().contract();
        StateStore store=context.stateStore();
        CoreDemoRunStore.CoreDemoRunRecord run=store.coreDemoRuns.findById(context.runId())
                .orElseThrow();
        String current=CoreDemoTreeHasher.hashWorkspace(
                context.workspace().workspace(),context.fixtureManifest()).aggregateHash();
        if(CoreDemoRunStore.REWIND_SUCCEEDED.equals(run.rewindStatus())) {
            if(!c.expectedBaselineTreeHash().equals(current))
                throw drift("DEMO_REWIND_HASH_DRIFT","completed rewind tree drift");
            CoreDemoBaselineResult checked=baseline.verify(
                    context.workspace().workspace(),context.fixtureManifest());
            requireBaseline(checked,c);
            return new CoreDemoRewindResult(run.rewindOccurrenceKey(),
                    resume.snapshotIds().reversed(),resume.finalTreeHash(),
                    c.expectedInitialImplementationTreeHash(),current,checked,true);
        }
        String owner="rewind-"+UUID.randomUUID().toString().replace("-","");
        store.coreDemoRuns.beginRewind(context.runId(),owner);
        boolean firstRestored=false;
        try {
            if(!resume.finalTreeHash().equals(current)
                    ||!c.expectedFinalTreeHash().equals(current))
                throw drift("DEMO_REWIND_HASH_DRIFT","rewind must start from final tree");
            List<FileSnapshotStore.FileSnapshotRecord> snapshots=
                    store.snapshots.findByTask(resume.taskId());
            if(snapshots.size()!=2||snapshots.stream().anyMatch(
                    x->!FileSnapshotStore.STATUS_APPLIED.equals(x.status())))
                throw drift("DEMO_REWIND_SNAPSHOT_DRIFT","snapshot set is not rewindable");
            if(!snapshots.stream().map(FileSnapshotStore.FileSnapshotRecord::snapshotId).toList()
                    .equals(resume.snapshotIds()))
                throw drift("DEMO_REWIND_SNAPSHOT_DRIFT","snapshot ownership/order drift");
            if(snapshots.stream().anyMatch(x->!x.taskId().equals(resume.taskId())
                    ||!x.filePath().replace('\\','/').equals(c.mutableFile())))
                throw drift("DEMO_REWIND_SNAPSHOT_DRIFT","snapshot identity drift");

            RewindService service=new RewindService(context.workspace().workspace(),store.snapshots);
            FileSnapshotStore.FileSnapshotRecord second=snapshots.get(1);
            FileSnapshotStore.FileSnapshotRecord first=snapshots.get(0);
            service.rewind(second.snapshotId());
            firstRestored=true;
            String intermediate=CoreDemoTreeHasher.hashWorkspace(
                    context.workspace().workspace(),context.fixtureManifest()).aggregateHash();
            if(!c.expectedInitialImplementationTreeHash().equals(intermediate))
                throw drift("DEMO_REWIND_HASH_DRIFT","first rewind did not restore initial tree");
            service.rewind(first.snapshotId());
            String seed=CoreDemoTreeHasher.hashWorkspace(
                    context.workspace().workspace(),context.fixtureManifest()).aggregateHash();
            if(!c.expectedBaselineTreeHash().equals(seed))
                throw drift("DEMO_REWIND_HASH_DRIFT","second rewind did not restore seed tree");
            CoreDemoBaselineResult checked=baseline.verify(
                    context.workspace().workspace(),context.fixtureManifest());
            requireBaseline(checked,c);
            store.coreDemoRuns.markRewindSucceeded(context.runId(),owner,seed);
            return new CoreDemoRewindResult(owner,
                    List.of(second.snapshotId(),first.snapshotId()),current,intermediate,seed,
                    checked,false);
        } catch(Exception error) {
            try{store.coreDemoRuns.markRewindFailed(context.runId(),owner,firstRestored,
                    firstRestored?"DEMO_PARTIAL_REWIND":"DEMO_REWIND_FAILED",error.getMessage());}
            catch(Exception ignored){}
            throw error;
        }
    }

    private static void requireBaseline(CoreDemoBaselineResult result,
            CoreDemoScenarioContract c) {
        if(result.status()!=CoreDemoBaselineResult.Status.BASELINE_PASSED
                ||result.tests()!=3||result.failures()!=0||result.errors()!=0
                ||result.skipped()!=0||!result.processCleaned()
                ||!result.buildResidueCleaned()
                ||!c.expectedBaselineTreeHash().equals(result.sourceTreeHash()))
            throw drift("DEMO_POST_REWIND_BASELINE_FAILED",
                    "post-rewind baseline contract mismatch");
    }
    private static CoreDemoScenarioDriftException drift(String category,String message){
        return new CoreDemoScenarioDriftException(category,message);
    }
}