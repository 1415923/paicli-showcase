package com.paicli.demo.workspace;

import com.paicli.demo.baseline.CoreDemoBaselineService;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoPreparationServiceIT {
    @TempDir Path root;
    @Test void onlyPassedOneShotPreparationIsAdmissible() throws Exception {
        Path repo=root.resolve("repo");Files.createDirectory(repo);
        var service=new CoreDemoPreparationService(new CoreDemoFixtureManifestLoader(),
                new CoreDemoWorkspaceService(root.resolve("runs"),repo),
                new CoreDemoBaselineService(Duration.ofSeconds(90)));
        var prepared=service.prepareOwned("run-00000003");
        assertTrue(prepared.readyForHeadlessRunner(),prepared.baselineResult().boundedExcerpt());
        assertEquals(CoreDemoWorkspaceStatus.BASELINE_PASSED,prepared.workspace().status());
        assertSame(prepared,service.prepareOwned("run-00000003"));
        assertFalse(Files.exists(prepared.workspace().workspace().resolve("target")));
    }
}
