package com.paicli.demo.workspace;
import com.paicli.demo.fixture.CoreDemoFixtureException;
import java.io.IOException;
@FunctionalInterface
public interface CoreDemoPreparationGateway {
    CoreDemoPreparationService.CoreDemoPreparation prepareForRun(
            String runId, CoreDemoPreparationService.PreparationObserver observer)
            throws CoreDemoFixtureException, IOException;
}
