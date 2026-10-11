package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** Test-only v4 authority checks that execute before any real Provider is constructed. */
final class FinalInternalAcceptanceV4Authority {
    static final String COMMIT = "62e7e553f68a57f05822454004f385817ff0de8c";
    static final String SRC_MAIN_TREE = "ed51f961218bdf73e95d1af391f3cce84d474ecf";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FinalInternalAcceptanceV4Authority() {}

    static JsonNode verify(Path project, Path authorityPath, Path artifactPath,
            Path overlayPath, Path evaluatorPath, String provider, String model) throws Exception {
        JsonNode gate = JSON.readTree(authorityPath.toFile());
        JsonNode artifact = JSON.readTree(artifactPath.toFile());
        JsonNode overlay = JSON.readTree(overlayPath.toFile());
        JsonNode evaluator = JSON.readTree(evaluatorPath.toFile());
        require("final-internal-acceptance-v4-provider-gate-v1".equals(
                gate.path("schemaVersion").asText()), "gate schema drift");
        require("final-internal-acceptance-v4".equals(gate.path("campaignIdentity").asText()),
                "campaign identity drift");
        require(COMMIT.equals(gate.path("candidateCommit").asText())
                        && SRC_MAIN_TREE.equals(gate.path("candidateSrcMainTree").asText()),
                "Candidate identity drift");
        require(provider.equals(gate.path("provider").asText())
                        && model.equals(gate.path("model").asText()),
                "scheduled Provider/model drift");
        require("final-internal-acceptance-v4-artifact-manifest-v1".equals(
                artifact.path("schemaVersion").asText()), "artifact schema drift");
        require(COMMIT.equals(artifact.path("candidateCommit").asText())
                        && SRC_MAIN_TREE.equals(artifact.path("candidateSrcMainTree").asText())
                        && artifact.path("providerCallsBeforeFreeze").asInt(-1) == 0,
                "artifact Candidate/freeze drift");
        require(treeDigest(project.resolve("target/classes")).equals(
                        artifact.path("classesSha256").asText()),
                "compiled Candidate artifact drift");
        require(treeDigest(project.resolve("target/test-classes")).equals(
                        artifact.path("compiledHarnessSha256").asText()),
                "compiled test-only harness drift");

        require("final-internal-acceptance-v4-harness-overlay-v1".equals(
                overlay.path("schemaVersion").asText()), "overlay schema drift");
        TreeMap<String, String> files = new TreeMap<>();
        overlay.path("files").fields().forEachRemaining(entry -> {
            try {
                String relative = entry.getKey().replace('\\', '/');
                Path path = project.resolve(relative).normalize();
                require(path.startsWith(project) && Files.isRegularFile(path)
                                && !Files.isSymbolicLink(path),
                        "overlay file missing or escaped: " + relative);
                require(!relative.startsWith("src/main/")
                                && !relative.startsWith("benchmarks/coding/fixtures/")
                                && !relative.startsWith("benchmarks/coding/templates/"),
                        "overlay crossed frozen boundary: " + relative);
                String digest = sha256(path);
                require(digest.equals(entry.getValue().asText()),
                        "overlay content drift: " + relative);
                files.put(relative, digest);
            } catch (Exception error) {
                throw new AuthorityRuntimeException(error);
            }
        });
        String harnessIdentity = "final-internal-acceptance-v4-harness-"
                + sha256(JSON.writeValueAsBytes(files));
        require(harnessIdentity.equals(overlay.path("harnessIdentity").asText())
                        && harnessIdentity.equals(gate.path("harnessIdentity").asText())
                        && harnessIdentity.equals(artifact.path("harnessIdentity").asText()),
                "harness identity disagreement");

        require("final-internal-acceptance-v4-evaluator-manifest-v1".equals(
                evaluator.path("schemaVersion").asText())
                        && "TASK_014_EXECUTABLE_REFACTOR_V1".equals(
                        evaluator.path("task014SemanticContract").asText())
                        && !evaluator.path("task014HelperCountMandatory").asBoolean(true)
                        && !evaluator.path("task014HistoricalModelOutputAuthority").asBoolean(true),
                "evaluator authority drift");
        evaluator.path("files").fields().forEachRemaining(entry -> {
            try {
                Path path = project.resolve(entry.getKey()).normalize();
                require(path.startsWith(project) && Files.isRegularFile(path)
                                && sha256(path).equals(entry.getValue().asText()),
                        "evaluator content drift: " + entry.getKey());
            } catch (Exception error) {
                throw new AuthorityRuntimeException(error);
            }
        });
        require(evaluator.path("evaluatorIdentity").asText().equals(
                        gate.path("evaluatorIdentity").asText())
                        && evaluator.path("evaluatorIdentity").asText().equals(
                        artifact.path("evaluatorIdentity").asText()),
                "evaluator identity disagreement");
        return gate;
    }

    static String treeDigest(Path root) throws Exception {
        require(Files.isDirectory(root), "compiled artifact root missing");
        TreeMap<String, String> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalStateException("artifact symlink rejected");
                if (Files.isRegularFile(path))
                    files.put(root.relativize(path).toString().replace('\\', '/'), sha256(path));
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
