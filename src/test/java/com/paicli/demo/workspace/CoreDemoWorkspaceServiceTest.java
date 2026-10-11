package com.paicli.demo.workspace;

import com.paicli.demo.fixture.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoWorkspaceServiceTest {
    @TempDir Path root;
    @Test void ownedLayoutExtractionRetentionAndIdempotentCleanup() throws Exception {
        Path demoRoot=root.resolve("demo-runs"),repo=root.resolve("repo");Files.createDirectory(repo);
        var service=new CoreDemoWorkspaceService(demoRoot,repo);
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var run=service.allocate("run-00000001",loaded.manifest().scenarioId(),loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        assertEquals(run.runRoot().resolve("workspace"),run.workspace());
        assertTrue(Files.isDirectory(run.state()));assertTrue(Files.isDirectory(run.artifacts()));assertTrue(Files.isDirectory(run.diagnostics()));
        new CoreDemoFixtureExtractor(new CoreDemoFixtureManifestLoader()).extract(run.workspace(),loaded);
        assertTrue(service.inspectCleanupEligibility(run).eligible());
        assertEquals(CoreDemoWorkspaceStatus.RETAINED_FOR_DIAGNOSTICS,service.retainForDiagnostics(run).status());
        var cleaned=service.cleanup(run);assertEquals(CoreDemoWorkspaceStatus.CLEANED,cleaned.status());assertFalse(Files.exists(run.runRoot()));
        assertEquals(CoreDemoWorkspaceStatus.CLEANED,service.cleanup(cleaned).status());
    }
    @Test void rootMarkerTamperingBlocksNewServiceAndRuntimeCleanupPreservesArtifactsAndState() throws Exception {
        Path repo=root.resolve("repo3");Files.createDirectory(repo);
        Path demoRoot=root.resolve("runs3");
        var service=new CoreDemoWorkspaceService(demoRoot,repo);
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var run=service.allocate("run-00000013",loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        new CoreDemoFixtureExtractor(new CoreDemoFixtureManifestLoader()).extract(run.workspace(),loaded);
        Files.writeString(run.artifacts().resolve("keep.txt"),"artifact");
        Files.writeString(run.state().resolve("keep.txt"),"state");
        var cleaned=service.cleanupSuccessfulRuntime(run);
        assertEquals(CoreDemoWorkspaceStatus.RUNTIME_CLEANED,cleaned.status());
        assertFalse(Files.exists(run.workspace()));assertFalse(Files.exists(run.diagnostics()));
        assertTrue(Files.exists(run.artifacts().resolve("keep.txt")));
        assertTrue(Files.exists(run.state().resolve("keep.txt")));
        Files.writeString(demoRoot.resolve(".paicli-demo-root.json"),"{}");
        assertThrows(IOException.class,()->new CoreDemoWorkspaceService(demoRoot,repo));
    }

    @Test void duplicateAndTamperedMarkerCannotBeCleaned() throws Exception {
        Path repo=root.resolve("repo");Files.createDirectory(repo);
        var service=new CoreDemoWorkspaceService(root.resolve("runs"),repo);
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var run=service.allocate("run-00000002",loaded.manifest().scenarioId(),loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        assertThrows(IOException.class,()->service.allocate("run-00000002",loaded.manifest().scenarioId(),loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash()));
        Files.writeString(run.runRoot().resolve("ownership.json"),"{}");
        assertFalse(service.inspectCleanupEligibility(run).eligible());
        assertThrows(IOException.class,()->service.cleanup(run));
        assertTrue(Files.exists(run.runRoot()));
    }

    @Test void cleanupRewalksAFileCreatedAfterDirectoryEnumeration() throws Exception {
        Path repo=root.resolve("repo-late");Files.createDirectory(repo);
        Path demoRoot=root.resolve("runs-late");
        AtomicInteger stateDeletes=new AtomicInteger();
        CoreDemoWorkspace[] allocated=new CoreDemoWorkspace[1];
        var service=new CoreDemoWorkspaceService(demoRoot,repo,(directory,pass)->{
            CoreDemoWorkspace run=allocated[0];
            if(run!=null&&directory.equals(run.state())&&stateDeletes.getAndIncrement()==0)
                Files.writeString(directory.resolve("paicli.db-shm"),"late-sidecar",
                        StandardOpenOption.CREATE_NEW);
        });
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var run=service.allocate("run-00000021",loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        allocated[0]=run;

        var cleaned=service.cleanup(run);

        assertEquals(CoreDemoWorkspaceStatus.CLEANED,cleaned.status());
        assertTrue(stateDeletes.get()>=2);
        assertFalse(Files.exists(run.runRoot(),LinkOption.NOFOLLOW_LINKS));
    }

    @Test void cleanupFailsClosedWhenAWriterKeepsTheOwnedTreeActive() throws Exception {
        Path repo=root.resolve("repo-active");Files.createDirectory(repo);
        Path demoRoot=root.resolve("runs-active");
        CoreDemoWorkspace[] allocated=new CoreDemoWorkspace[1];
        var service=new CoreDemoWorkspaceService(demoRoot,repo,(directory,pass)->{
            CoreDemoWorkspace run=allocated[0];
            if(run!=null&&directory.equals(run.state()))
                Files.writeString(directory.resolve("active-writer-"+pass),"active",
                        StandardOpenOption.CREATE_NEW);
        });
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var run=service.allocate("run-00000022",loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        allocated[0]=run;

        IOException error=assertThrows(IOException.class,()->service.cleanup(run));

        assertEquals("Owned tree remained active during bounded cleanup",error.getMessage());
        assertInstanceOf(DirectoryNotEmptyException.class,error.getCause());
        assertTrue(Files.exists(run.runRoot(),LinkOption.NOFOLLOW_LINKS));
    }
}
