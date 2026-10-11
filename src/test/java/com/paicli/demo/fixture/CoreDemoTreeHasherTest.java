package com.paicli.demo.fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoTreeHasherTest {
    @TempDir Path root;
    @Test void hashIsPathIndependentAndDetectsContentAndExtraFiles() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader(); var loaded=loader.load();
        var extractor=new CoreDemoFixtureExtractor(loader);
        Path one=root.resolve("one"),two=root.resolve("two with spaces");
        String first=extractor.extract(one,loaded).aggregateHash();
        String second=extractor.extract(two,loaded).aggregateHash();
        assertEquals(first,second); assertEquals(loaded.manifest().expectedBaselineTreeHash(),first);
        Files.writeString(one.resolve("src/main/java/com/paicli/demo/order/PricingService.java"),"changed");
        assertNotEquals(first,CoreDemoTreeHasher.hashWorkspace(one,loaded.manifest()).aggregateHash());
        Files.writeString(two.resolve("src/main/java/Extra.java"),"class Extra {}");
        assertThrows(CoreDemoFixtureException.class,()->CoreDemoTreeHasher.hashWorkspace(two,loaded.manifest()));
    }
    @Test void resourceTextBytesAreLfOnly() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader(); var loaded=loader.load();
        for(var file:loaded.manifest().files()) assertFalse(new String(loader.readSeedFile(file),java.nio.charset.StandardCharsets.UTF_8).contains("\r"));
        assertFalse(new String(loaded.manifestBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("\r"));
    }
}
