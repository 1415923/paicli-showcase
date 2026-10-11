package com.paicli.demo.fixture;

public record CoreDemoFixtureFile(String relativePath, long sizeBytes, String sha256, Role role) {
    public enum Role { MUTABLE_SOURCE, IMMUTABLE_SOURCE, IMMUTABLE_TEST, BUILD_DESCRIPTOR }
}
