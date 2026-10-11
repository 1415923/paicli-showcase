package com.paicli.demo.fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoFixtureExtractorTest {
    @TempDir Path root;
    @Test void extractsExactManifestFilesWithCreateNewSemantics() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader();var loaded=loader.load();var extractor=new CoreDemoFixtureExtractor(loader);
        Path workspace=root.resolve("workspace");
        var snapshot=extractor.extract(workspace,loaded);
        assertEquals(loaded.manifest().expectedBaselineTreeHash(),snapshot.aggregateHash());
        assertEquals(loaded.manifest().files().size(),snapshot.files().size());
        assertThrows(CoreDemoFixtureException.class,()->extractor.extract(workspace,loaded));
        assertFalse(Files.exists(workspace.resolve("unknown")));
    }
    @Test void missingClasspathResourceFailsClosed() {
        ClassLoader empty=new ClassLoader(null){@Override public java.io.InputStream getResourceAsStream(String name){return null;}};
        assertThrows(CoreDemoFixtureException.class,()->new CoreDemoFixtureManifestLoader(empty).load());
    }
}
