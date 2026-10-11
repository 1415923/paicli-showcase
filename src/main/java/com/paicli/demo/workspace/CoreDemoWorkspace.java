package com.paicli.demo.workspace;

import java.nio.file.Path;

/** Immutable owned workspace handle; only the workspace package can create or advance it. */
public final class CoreDemoWorkspace {
    private final String runId;
    private final Path runRoot;
    private final Path workspace;
    private final Path state;
    private final Path artifacts;
    private final Path diagnostics;
    private final String manifestHash;
    private final String expectedSeedHash;
    private final String workspaceIdentityHash;
    private final CoreDemoWorkspaceStatus status;

    CoreDemoWorkspace(String runId, Path runRoot, Path workspace, Path state, Path artifacts,
            Path diagnostics, String manifestHash, String expectedSeedHash,
            String workspaceIdentityHash, CoreDemoWorkspaceStatus status) {
        this.runId=runId; this.runRoot=runRoot; this.workspace=workspace; this.state=state;
        this.artifacts=artifacts; this.diagnostics=diagnostics; this.manifestHash=manifestHash;
        this.expectedSeedHash=expectedSeedHash; this.workspaceIdentityHash=workspaceIdentityHash;
        this.status=status;
    }

    public String runId(){return runId;}
    public Path runRoot(){return runRoot;}
    public Path workspace(){return workspace;}
    public Path state(){return state;}
    public Path artifacts(){return artifacts;}
    public Path diagnostics(){return diagnostics;}
    public String manifestHash(){return manifestHash;}
    public String expectedSeedHash(){return expectedSeedHash;}
    public String workspaceIdentityHash(){return workspaceIdentityHash;}
    public CoreDemoWorkspaceStatus status(){return status;}

    CoreDemoWorkspace withStatus(CoreDemoWorkspaceStatus value) {
        return new CoreDemoWorkspace(runId,runRoot,workspace,state,artifacts,diagnostics,
                manifestHash,expectedSeedHash,workspaceIdentityHash,value);
    }
}
