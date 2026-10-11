package com.paicli.demo.checkpoint;
import com.paicli.state.CoreDemoCheckpointStore;
@FunctionalInterface
public interface CoreDemoCheckpointHook {
    void afterCheckpoint(CoreDemoCheckpointStore.CheckpointRecord checkpoint);
    static CoreDemoCheckpointHook none(){return checkpoint -> {};}
}