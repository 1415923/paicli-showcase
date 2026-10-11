package com.paicli.demo.artifact;
/** Opaque workflow binding. It grants no filesystem or artifact-write capability. */
public record CoreDemoArtifactScope(String runId,String fixtureManifestHash) {
    public CoreDemoArtifactScope {
        if(runId==null||!runId.matches("[a-z0-9][a-z0-9-]{7,63}"))
            throw new IllegalArgumentException("Invalid artifact run identity");
        if(fixtureManifestHash==null||!fixtureManifestHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid artifact manifest identity");
    }
}
