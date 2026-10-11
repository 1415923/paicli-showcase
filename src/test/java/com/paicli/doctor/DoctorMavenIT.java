package com.paicli.doctor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoctorMavenIT {
    @TempDir Path root;

    @Test
    void realMavenVersionProbeIsBoundedAndAvailable() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DoctorReport report = new DoctorService(root).inspect();
        DoctorCheck maven = report.checks().stream()
                .filter(check -> "Maven".equals(check.name()))
                .findFirst().orElseThrow();
        assertEquals(DoctorStatus.PASS, maven.status(), maven.detail());
    }
}
