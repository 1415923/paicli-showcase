package com.paicli.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCandidateDigestTest {
    @TempDir Path workspace;

    @Test
    void digestIsStableAcrossInstancesAndCoversOrdinaryInputs() throws Exception {
        write("src/main/java/App.java", "class App {}\n");
        write("src/test/java/AppTest.java", "class AppTest {}\n");
        write("src/main/resources/app.properties", "enabled=true\n");
        write("pom.xml", "<project/>\n");
        write("scripts/check.ps1", "Write-Output ok\n");
        write(".env.example", "EXAMPLE=true\n");

        WorkspaceCandidateDigest.Result first = digest();
        WorkspaceCandidateDigest.Result repeated = digest();

        assertEquals(WorkspaceCandidateDigest.KIND, first.kind());
        assertEquals(64, first.digest().length());
        assertEquals(first, repeated);

        assertChanged(first, "src/main/java/App.java", "class App { int changed; }\n");
        first = digest();
        assertChanged(first, "src/test/java/NewTest.java", "class NewTest {}\n");
        first = digest();
        assertChanged(first, "src/main/resources/app.properties", "enabled=false\n");
        first = digest();
        assertChanged(first, "pom.xml", "<project><version>2</version></project>\n");
        first = digest();
        assertChanged(first, "scripts/check.ps1", "Write-Output changed\n");
        first = digest();
        assertChanged(first, ".env.example", "EXAMPLE=false\n");
    }

    @Test
    void excludedRuntimeVcsAndBuildTreesDoNotChangeDigest() throws Exception {
        write("src/main/java/App.java", "class App {}\n");
        WorkspaceCandidateDigest.Result expected = digest();

        for (String path : new String[]{
                ".git/index", ".paicli/state/runtime.db", "target/classes/App.class",
                "build/output.bin", "out/result.txt", ".gradle/cache.bin",
                ".idea/workspace.xml", "node_modules/package/index.js"}) {
            write(path, "generated-1");
            assertEquals(expected.digest(), digest().digest(), path);
            write(path, "generated-2");
            assertEquals(expected.digest(), digest().digest(), path);
        }
    }

    @Test
    void ordinaryFilesNamedLikeBuildDirectoriesRemainCandidateInputs() throws Exception {
        WorkspaceCandidateDigest.Result expected = digest();
        for (String name : new String[]{"target", "build", "out", ".gradle", ".idea",
                "node_modules", ".paicli"}) {
            write(name, "ordinary-input");
            WorkspaceCandidateDigest.Result changed = digest();
            assertNotEquals(expected.digest(), changed.digest(), name);
            Files.delete(workspace.resolve(name));
            assertEquals(expected.digest(), digest().digest(), name);
        }
        write(".git", "gitdir: elsewhere");
        assertEquals(expected.digest(), digest().digest(), ".git metadata file");
    }

    @Test
    void deletionAndRenameChangeDigest() throws Exception {
        write("src/main/java/App.java", "class App {}\n");
        WorkspaceCandidateDigest.Result original = digest();
        Files.move(workspace.resolve("src/main/java/App.java"),
                workspace.resolve("src/main/java/Renamed.java"));
        WorkspaceCandidateDigest.Result renamed = digest();
        assertNotEquals(original.digest(), renamed.digest());
        Files.delete(workspace.resolve("src/main/java/Renamed.java"));
        assertNotEquals(renamed.digest(), digest().digest());
    }

    @Test
    void symbolicLinkFailsClosedWhenPlatformAllowsCreation() throws Exception {
        write("ordinary.txt", "content");
        Path target = workspace.resolve("ordinary.txt");
        Path link = workspace.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException error) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + error);
        }
        WorkspaceCandidateDigest.Failure failure = assertThrows(
                WorkspaceCandidateDigest.Failure.class, this::digest);
        assertEquals(WorkspaceCandidateDigest.Code.UNSAFE_ENTRY, failure.code());
    }

    private WorkspaceCandidateDigest.Result digest() throws Exception {
        return new WorkspaceCandidateDigest(workspace).compute();
    }

    private void assertChanged(WorkspaceCandidateDigest.Result before, String path, String content)
            throws Exception {
        write(path, content);
        assertNotEquals(before.digest(), digest().digest(), path);
    }

    private void write(String relative, String content) throws Exception {
        Path file = workspace.resolve(relative);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
