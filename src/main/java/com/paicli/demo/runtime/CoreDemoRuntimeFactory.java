package com.paicli.demo.runtime;

import com.paicli.demo.artifact.CoreDemoArtifactWriter;
import com.paicli.demo.baseline.CoreDemoBaselineService;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;
import com.paicli.demo.workflow.CoreDemoWorkflowFactory;
import com.paicli.demo.workspace.*;
import java.nio.file.Path;
import java.time.Duration;

public final class CoreDemoRuntimeFactory {
    private CoreDemoRuntimeFactory(){}
    public static HeadlessCoreDemoRunner production() throws Exception {
        Path root=CoreDemoRootResolver.defaultRoot();
        Path protectedRepository=Path.of(System.getProperty("user.dir")).toRealPath();
        CoreDemoWorkspaceService workspaces=new CoreDemoWorkspaceService(root,protectedRepository);
        CoreDemoPreparationService preparation=new CoreDemoPreparationService(
                new CoreDemoFixtureManifestLoader(),workspaces,
                new CoreDemoBaselineService(Duration.ofSeconds(90)));
        return new HeadlessCoreDemoRunner(preparation,workspaces,
                CoreDemoWorkflowFactory.production(),new CoreDemoArtifactWriter());
    }
}