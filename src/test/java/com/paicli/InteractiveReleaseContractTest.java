package com.paicli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveReleaseContractTest {
    @Test void versionHelpReadmeLaunchersAndManifestUse120() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, PaiCLI.run(new String[]{"--version"}));
            assertEquals(0, PaiCLI.run(new String[]{"--help"}));
        } finally {
            System.setOut(original);
        }
        String cli = output.toString(StandardCharsets.UTF_8);
        assertTrue(cli.contains("PaiCLI 1.2.0"));
        assertTrue(cli.contains("paicli                            Enter interactive mode"));
        assertTrue(cli.contains(
                "Interactive: /sessions /resume /new /task recover"));
        assertTrue(cli.contains("/diff /tasks /rewind /report /doctor"));

        String readme = Files.readString(Path.of("README.md"));
        assertTrue(readme.contains("paicli-1.2.0.zip"));
        assertTrue(readme.contains("/provider"));
        assertTrue(readme.contains("/tasks"));
        assertTrue(readme.contains("/resume"));
        assertTrue(readme.contains("/rewind"));
        assertTrue(readme.contains("/report"));
        assertFalse(readme.contains("mock (default)"));

        assertTrue(Files.readString(Path.of("src/dist/bin/paicli.ps1"))
                .contains("paicli-1.2.0.jar"));
        assertTrue(Files.readString(Path.of("src/dist/bin/paicli.cmd"))
                .contains("paicli-1.2.0.jar"));
        assertTrue(Files.readString(Path.of("src/dist/bin/paicli"))
                .contains("paicli-1.2.0.jar"));

        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<version>1.2.0</version>"));
        assertTrue(pom.contains(
                "<Implementation-Version>${project.version}</Implementation-Version>"));
        assertFalse(pom.contains(
                "<Implementation-Version>1.2.0</Implementation-Version>"));
    }
}
