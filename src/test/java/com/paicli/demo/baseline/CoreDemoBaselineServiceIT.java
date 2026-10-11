package com.paicli.demo.baseline;

import com.paicli.demo.fixture.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoBaselineServiceIT {
    @TempDir Path root;
    @Test void realOfflineBaselineCountsThreeTestsCleansTargetAndIsDeterministic() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader();var loaded=loader.load();var extractor=new CoreDemoFixtureExtractor(loader);
        var service=new CoreDemoBaselineService(Duration.ofSeconds(90));
        Path one=root.resolve("one"),two=root.resolve("two with spaces");
        extractor.extract(one,loaded); extractor.extract(two,loaded);
        var first=service.verify(one,loaded.manifest()); var second=service.verify(two,loaded.manifest());
        assertEquals(CoreDemoBaselineResult.Status.BASELINE_PASSED,first.status(),first.boundedExcerpt());
        assertEquals(3,first.tests());assertEquals(0,first.failures());assertEquals(0,first.errors());assertEquals(0,first.skipped());
        assertEquals(first.sourceTreeHash(),second.sourceTreeHash());assertEquals(first.tests(),second.tests());
        assertFalse(Files.exists(one.resolve("target")));assertFalse(Files.exists(two.resolve("target")));
    }
    @Test void driftPreventsMavenStart() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader();var loaded=loader.load();Path workspace=root.resolve("drift");
        new CoreDemoFixtureExtractor(loader).extract(workspace,loaded);
        Files.writeString(workspace.resolve("pom.xml"),"changed");
        var result=new CoreDemoBaselineService(Duration.ofSeconds(5),(path)->{fail("must not start");return null;}).verify(workspace,loaded.manifest());
        assertEquals(CoreDemoBaselineResult.Status.DEMO_BASELINE_INVALID,result.status());
    }
    @Test void timeoutIsBoundedAndProcessIsTerminated() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader();var loaded=loader.load();Path workspace=root.resolve("timeout");
        new CoreDemoFixtureExtractor(loader).extract(workspace,loaded);
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        var result=new CoreDemoBaselineService(Duration.ofMillis(200),(path)->new ProcessBuilder(
                java,"-cp",System.getProperty("java.class.path"),SleepingMain.class.getName()).start())
                .verify(workspace,loaded.manifest());
        assertTrue(result.timedOut()); assertTrue(result.processCleaned());
        assertEquals(CoreDemoBaselineResult.Status.DEMO_BASELINE_INVALID,result.status());
    }

    @Test void malformedSurefireReportFailsClosedAndIsCleaned() throws Exception {
        var loader=new CoreDemoFixtureManifestLoader();var loaded=loader.load();Path workspace=root.resolve("malformed");
        new CoreDemoFixtureExtractor(loader).extract(workspace,loaded);
        Path reports=workspace.resolve("target/surefire-reports");Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-bad.xml"),"<testsuite tests='x'/>");
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
        var result=new CoreDemoBaselineService(Duration.ofSeconds(5),(path)->new ProcessBuilder(java,"-version").start())
                .verify(workspace,loaded.manifest());
        assertEquals(CoreDemoBaselineResult.Status.BASELINE_ERROR,result.status());
        assertFalse(Files.exists(workspace.resolve("target")));
    }

    public static final class SleepingMain {
        public static void main(String[] args) throws Exception { Thread.sleep(30_000); }
    }

}
