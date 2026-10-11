package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Candidate/artifact/harness admission checks that must run before Provider construction. */
final class FinalSourceV2Authority {
    static final String COMMIT = "000318ebc36904249520161831f98112402f567d";
    static final String SRC_MAIN_TREE = "26eba5343b859b7f3c41f8462702d6f30e5a1c99";
    static final String ARTIFACT_KIND = "MAVEN_TARGET_CLASSES_TREE_SHA256_V1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FinalSourceV2Authority() {}

    static JsonNode verify(Path project, Path authorityPath, Path artifactPath,
            Path overlayPath) throws Exception {
        JsonNode authority = JSON.readTree(authorityPath.toFile());
        JsonNode artifact = JSON.readTree(artifactPath.toFile());
        JsonNode overlay = JSON.readTree(overlayPath.toFile());
        require("formal-final-source-v2-provider-gate-v1".equals(
                authority.path("schemaVersion").asText()), "gate schema drift");
        require("final-source-internal-controlled-regression-v2".equals(
                authority.path("campaignIdentity").asText()), "campaign identity drift");
        require("formal-final-source-result-v2".equals(
                authority.path("resultIdentity").asText()), "result identity drift");
        require("formal-final-source-evidence-v2".equals(
                authority.path("evidenceIdentity").asText()), "evidence identity drift");
        JsonNode launch = authority.path("launch");
        require("final-source-v2-launch-v1".equals(
                launch.path("schemaVersion").asText()), "launch schema drift");
        require(launch.path("paidExecutionRequiresExplicitRuntimeFlag").asBoolean(false)
                        && !launch.path("persistentPaidAuthorization").asBoolean(true)
                        && "--execute-paid".equals(launch.path("runtimeAuthorizationFlag").asText())
                        && !launch.has("paidProviderAuthorized"),
                "immutable launch authorization model drift");
        require(COMMIT.equals(authority.path("candidateCommit").asText()),
                "Candidate commit drift");
        require(SRC_MAIN_TREE.equals(authority.path("candidateSrcMainTree").asText()),
                "Candidate src/main tree drift");
        require(ARTIFACT_KIND.equals(artifact.path("artifactKind").asText()),
                "artifact kind drift");
        require("final-source-v2-artifact-manifest-v1".equals(
                artifact.path("schemaVersion").asText()), "artifact schema drift");
        require(COMMIT.equals(artifact.path("artifactSourceCommit").asText()),
                "artifact source commit drift");
        require(SRC_MAIN_TREE.equals(artifact.path("artifactSrcMainTree").asText()),
                "artifact source tree drift");
        require(artifact.path("providerCallsBeforeFreeze").asInt(-1) == 0,
                "artifact was not frozen before Provider admission");

        String actualArtifact = treeDigest(project.resolve("target/classes"));
        require(actualArtifact.equals(artifact.path("artifactSha256").asText()),
                "target/classes artifact drift");
        require(actualArtifact.equals(authority.path("artifactSha256").asText()),
                "gate/artifact SHA disagreement");

        require("final-source-v2-harness-overlay-v1".equals(
                overlay.path("schemaVersion").asText()), "overlay schema drift");
        TreeMap<String, String> actualFiles = new TreeMap<>();
        overlay.path("files").fields().forEachRemaining(entry -> {
            try {
                Path file = project.resolve(entry.getKey()).normalize();
                String relative = entry.getKey().replace('\\', '/');
                require(file.startsWith(project) && Files.isRegularFile(file)
                                && !Files.isSymbolicLink(file),
                        "overlay file is missing or escaped: " + entry.getKey());
                require(!relative.startsWith("src/main/")
                                && !relative.startsWith("benchmarks/coding/fixtures/")
                                && !relative.startsWith("benchmarks/coding/templates/")
                                && !relative.startsWith("benchmarks/coding/profiles/")
                                && !relative.startsWith("benchmarks/coding/schema/")
                                && !relative.equals("src/test/java/com/paicli/benchmark/"
                                        + "SimpleToolLoopBaseline.java"),
                        "overlay crossed frozen semantics boundary: " + entry.getKey());
                String actual = sha256(file);
                require(actual.equals(entry.getValue().asText()),
                        "overlay content drift: " + entry.getKey());
                actualFiles.put(entry.getKey().replace('\\', '/'), actual);
            } catch (Exception error) {
                throw new AuthorityRuntimeException(error);
            }
        });
        TreeMap<String, Object> material = new TreeMap<>();
        material.put("files", actualFiles);
        material.put("schemaVersion", "final-source-v2-harness-overlay-v1");
        String identity = "final-source-harness-v2-" + sha256(
                JSON.writeValueAsBytes(material));
        require(identity.equals(overlay.path("harnessIdentity").asText()),
                "aggregate harness identity drift");
        require(identity.equals(authority.path("harnessIdentity").asText()),
                "gate/harness identity disagreement");
        require(identity.equals(artifact.path("harnessIdentity").asText()),
                "artifact/harness identity disagreement");
        require(authority.path("harnessManifestSha256").asText().equals(
                        artifact.path("harnessManifestSha256").asText()),
                "artifact/harness manifest disagreement");
        return authority;
    }

    static String treeDigest(Path root) throws Exception {
        require(Files.isDirectory(root), "target/classes is missing");
        TreeMap<String, String> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isSymbolicLink(path))
                    throw new IllegalStateException("artifact symlink rejected");
                if (Files.isRegularFile(path))
                    files.put(root.relativize(path).toString().replace('\\', '/'),
                            sha256(path));
            }
        }
        return sha256(JSON.writeValueAsBytes(files));
    }

    private static String sha256(Path path) throws Exception {
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class AuthorityRuntimeException extends RuntimeException {
        private AuthorityRuntimeException(Exception cause) { super(cause); }
    }
}
