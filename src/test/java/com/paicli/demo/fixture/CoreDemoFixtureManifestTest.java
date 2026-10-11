package com.paicli.demo.fixture;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoFixtureManifestTest {
    @Test void productionManifestFreezesScenarioMutationAndResources() throws Exception {
        var loaded=new CoreDemoFixtureManifestLoader().load();
        var m=loaded.manifest();
        assertEquals(1,m.manifestSchemaVersion());
        assertEquals("core-java-member-discount-v1",m.scenarioId());
        assertEquals(3,m.expectedBaselineTestCount());
        assertEquals(List.of("src/main/java/com/paicli/demo/order/PricingService.java"),m.allowedMutablePaths());
        assertEquals(8,m.files().size());
        assertTrue(m.files().stream().filter(f->f.relativePath().equals("pom.xml")).allMatch(f->f.role()==CoreDemoFixtureFile.Role.BUILD_DESCRIPTOR));
        assertTrue(m.files().stream().filter(f->f.relativePath().startsWith("src/test/")).allMatch(f->f.role()==CoreDemoFixtureFile.Role.IMMUTABLE_TEST));
        assertFalse(new String(loaded.manifestBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("mvn "));
    }
    @Test void rejectsUnsafePathsDuplicateAndUnsupportedVersion() throws Exception {
        for(String path:List.of("../x","/x","C:/x","//server/x","x:y","x\\y","x/../y","x\u0000y"))
            assertThrows(CoreDemoFixtureException.class,()->CoreDemoFixtureManifestLoader.validateRelativePath(path));
        var m=new CoreDemoFixtureManifestLoader().load().manifest();
        var duplicate=new ArrayList<>(m.files()); duplicate.add(m.files().getFirst());
        assertThrows(CoreDemoFixtureException.class,()->CoreDemoFixtureManifestLoader.validate(copy(m,1,duplicate,m.allowedMutablePaths())));
        assertThrows(CoreDemoFixtureException.class,()->CoreDemoFixtureManifestLoader.validate(copy(m,2,m.files(),m.allowedMutablePaths())));
        assertThrows(CoreDemoFixtureException.class,()->CoreDemoFixtureManifestLoader.validate(copy(m,1,m.files(),List.of("pom.xml"))));
    }
    private static CoreDemoFixtureManifest copy(CoreDemoFixtureManifest m,int version,List<CoreDemoFixtureFile> files,List<String> allowed){
        return new CoreDemoFixtureManifest(version,m.scenarioId(),m.fixtureVersion(),m.displayName(),m.javaRelease(),m.baselineCommandId(),m.expectedBaselineTestCount(),m.expectedBaselineTreeHash(),allowed,files);
    }
}
