package com.paicli.demo.fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoProductionResourceTest {
    @TempDir Path root;
    @Test void loaderWorksFromNonFilesystemJarClasspath() throws Exception {
        var source=new CoreDemoFixtureManifestLoader();var loaded=source.load();Path jar=root.resolve("fixture.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar))){
            put(out,CoreDemoFixtureManifestLoader.MANIFEST_RESOURCE,loaded.manifestBytes());
            for(var file:loaded.manifest().files()) put(out,CoreDemoFixtureManifestLoader.SEED_ROOT+file.relativePath(),source.readSeedFile(file));
        }
        try(var classLoader=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},null)){
            var fromJar=new CoreDemoFixtureManifestLoader(classLoader).load();
            assertEquals(loaded.manifest().expectedBaselineTreeHash(),fromJar.manifest().expectedBaselineTreeHash());
        }
    }

    @Test void extraControlledJarResourceIsRejected() throws Exception {
        var source=new CoreDemoFixtureManifestLoader();var loaded=source.load();Path jar=root.resolve("extra.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar))){
            put(out,CoreDemoFixtureManifestLoader.MANIFEST_RESOURCE,loaded.manifestBytes());
            for(var file:loaded.manifest().files()) put(out,CoreDemoFixtureManifestLoader.SEED_ROOT+file.relativePath(),source.readSeedFile(file));
            put(out,CoreDemoFixtureManifestLoader.SEED_ROOT+"src/main/java/Unexpected.java","class Unexpected {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try(var classLoader=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},null)){
            assertThrows(CoreDemoFixtureException.class,()->new CoreDemoFixtureManifestLoader(classLoader).load());
        }
    }
    private static void put(JarOutputStream out,String name,byte[] bytes)throws Exception{out.putNextEntry(new JarEntry(name));out.write(bytes);out.closeEntry();}
}
