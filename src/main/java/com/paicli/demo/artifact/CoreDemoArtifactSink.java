package com.paicli.demo.artifact;
import java.io.IOException;
import java.nio.file.Path;
@FunctionalInterface
public interface CoreDemoArtifactSink {
    CoreDemoArtifactCommit write(Path ownedArtifactsRoot, CoreDemoArtifactInput input)
            throws IOException;
}
