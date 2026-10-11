package com.paicli.demo.runtime;
import com.paicli.demo.workspace.CoreDemoWorkspace;
import com.paicli.state.StateStore;
@FunctionalInterface
public interface CoreDemoStateStoreFactory {
    StateStore open(CoreDemoWorkspace workspace) throws Exception;
    static CoreDemoStateStoreFactory production() {
        return workspace -> StateStore.openCoreDemo(
                workspace.state().resolve("paicli.db"), workspace.runRoot());
    }
}
