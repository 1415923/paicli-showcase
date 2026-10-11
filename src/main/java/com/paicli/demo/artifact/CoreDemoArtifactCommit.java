package com.paicli.demo.artifact;
import java.util.List;
public record CoreDemoArtifactCommit(String semanticResultHash,
        String bundleManifestHash, List<FileFact> files, boolean atomicMoveUsed) {
    public CoreDemoArtifactCommit { files=List.copyOf(files); }
    public record FileFact(String relativePath,long sizeBytes,String sha256) {}
}
