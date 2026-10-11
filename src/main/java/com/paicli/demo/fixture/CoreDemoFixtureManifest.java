package com.paicli.demo.fixture;

import java.util.List;

public record CoreDemoFixtureManifest(
        int manifestSchemaVersion,
        String scenarioId,
        String fixtureVersion,
        String displayName,
        int javaRelease,
        String baselineCommandId,
        int expectedBaselineTestCount,
        String expectedBaselineTreeHash,
        List<String> allowedMutablePaths,
        List<CoreDemoFixtureFile> files) {
    public CoreDemoFixtureManifest {
        allowedMutablePaths = List.copyOf(allowedMutablePaths == null ? List.of() : allowedMutablePaths);
        files = List.copyOf(files == null ? List.of() : files);
    }
}
