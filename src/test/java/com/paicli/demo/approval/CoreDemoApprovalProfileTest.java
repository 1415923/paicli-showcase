package com.paicli.demo.approval;

import com.paicli.demo.fixture.*;
import com.paicli.demo.workspace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoApprovalProfileTest {
    @TempDir Path root;
    @Test void exactManifestPolicyCannotBeExpanded() throws Exception {
        Path repo=root.resolve("repo");Files.createDirectory(repo);
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var service=new CoreDemoWorkspaceService(root.resolve("runs"),repo);
        var run=service.allocate("run-00000011",loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        new CoreDemoFixtureExtractor(new CoreDemoFixtureManifestLoader())
                .extract(run.workspace(),loaded);
        var profile=CoreDemoApprovalProfile.derive(run,loaded.manifest());
        String pricing="src/main/java/com/paicli/demo/order/PricingService.java";
        assertTrue(profile.evaluate("read_file",pricing,
                CoreDemoApprovalProfile.Mutation.READ).allowed());
        var write=profile.evaluate("write_file",pricing,
                CoreDemoApprovalProfile.Mutation.WRITE);
        assertTrue(write.allowed());assertTrue(write.snapshotRequired());
        for(String path:new String[]{"pom.xml",
                "src/test/java/com/paicli/demo/order/PricingServiceTest.java",
                "src/main/java/com/paicli/demo/order/Order.java"}){
            assertFalse(profile.evaluate("write_file",path,
                    CoreDemoApprovalProfile.Mutation.WRITE).allowed());
        }
        assertFalse(profile.evaluate("write_file","new.java",
                CoreDemoApprovalProfile.Mutation.CREATE).allowed());
        for(String tool:new String[]{"run_command","git","mcp.server.tool",
                "mailbox_post","worker","subagent","shell"}){
            assertFalse(profile.evaluate(tool,pricing,
                    CoreDemoApprovalProfile.Mutation.EXECUTE).allowed());
        }
        assertTrue(profile.evaluate("code_intelligence",pricing,
                CoreDemoApprovalProfile.Mutation.QUERY).allowed());
        assertTrue(profile.evaluate("configured_verification",pricing,
                CoreDemoApprovalProfile.Mutation.VERIFY).allowed());
        assertFalse(profile.interactiveApprovalAvailable());
        assertEquals(java.util.Set.of(pricing),profile.mutablePaths());
    }

    @Test void outsideAndUnsafePathsFailClosed() throws Exception {
        Path repo=root.resolve("repo2");Files.createDirectory(repo);
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var service=new CoreDemoWorkspaceService(root.resolve("runs2"),repo);
        var run=service.allocate("run-00000012",loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        new CoreDemoFixtureExtractor(new CoreDemoFixtureManifestLoader())
                .extract(run.workspace(),loaded);
        var profile=CoreDemoApprovalProfile.derive(run,loaded.manifest());
        assertFalse(profile.evaluate("read_file","../secret",
                CoreDemoApprovalProfile.Mutation.READ).allowed());
        assertFalse(profile.evaluate("write_file","C:/secret",
                CoreDemoApprovalProfile.Mutation.WRITE).allowed());
    }
}
