package com.paicli.demo.workspace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.demo.fixture.CoreDemoFixtureException;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CoreDemoWorkspaceService {
    private static final int MAX_DELETE_PASSES = 4;
    private static final Pattern RUN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{7,63}");
    private static final String MARKER = "ownership.json";
    private static final String ROOT_MARKER = ".paicli-demo-root.json";
    private final Path trustedRoot;
    private final Path protectedRepository;
    private final DirectoryDeleteObserver directoryDeleteObserver;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CoreDemoWorkspaceService(Path trustedRoot, Path protectedRepository) throws IOException {
        this(trustedRoot, protectedRepository, DirectoryDeleteObserver.NONE);
    }

    CoreDemoWorkspaceService(Path trustedRoot, Path protectedRepository,
            DirectoryDeleteObserver directoryDeleteObserver) throws IOException {
        this.trustedRoot = requireTrustedRoot(trustedRoot);
        ensureRootOwnership();
        this.protectedRepository = protectedRepository == null ? null : protectedRepository.toRealPath();
        this.directoryDeleteObserver = Objects.requireNonNull(directoryDeleteObserver);
        if (this.protectedRepository != null && overlaps(this.trustedRoot, this.protectedRepository))
            throw new IOException("Demo root overlaps protected repository");
    }

    private void ensureRootOwnership() throws IOException {
        Path markerPath = trustedRoot.resolve(ROOT_MARKER);
        String identity = hash("demo-root-v1\0" + trustedRoot);
        RootMarker expected = new RootMarker(1, identity);
        if (!Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(markerPath, json.writeValueAsString(expected) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return;
        }
        if (Files.isSymbolicLink(markerPath)
                || !Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Demo root ownership marker is unsafe");
        }
        RootMarker actual = json.readValue(Files.readAllBytes(markerPath), RootMarker.class);
        if (!expected.equals(actual)) throw new IOException("Demo root ownership mismatch");
    }

    public CoreDemoWorkspace allocate(String runId, String scenarioId, String manifestHash,
            String expectedSeedHash) throws IOException, CoreDemoFixtureException {
        requireRunId(runId);
        Path runRoot = trustedRoot.resolve(runId).normalize();
        if (!runRoot.getParent().equals(trustedRoot) || Files.exists(runRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Demo run root is not new");
        Files.createDirectory(runRoot);
        Path canonicalRun = runRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireContained(canonicalRun);
        Path workspace = canonicalRun.resolve("workspace");
        Path state = canonicalRun.resolve("state");
        Path artifacts = canonicalRun.resolve("artifacts");
        Path diagnostics = canonicalRun.resolve("diagnostics");
        Files.createDirectory(state); Files.createDirectory(artifacts); Files.createDirectory(diagnostics);
        String identity = hash("workspace-v1\0" + workspace.toAbsolutePath().normalize()
                + "\0" + runId + "\0" + scenarioId + "\0" + manifestHash);
        Marker marker = new Marker(1, runId, scenarioId, manifestHash, expectedSeedHash, identity, Instant.now().toString());
        Files.writeString(canonicalRun.resolve(MARKER), json.writeValueAsString(marker) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new CoreDemoWorkspace(runId, canonicalRun, workspace, state, artifacts, diagnostics,
                manifestHash, expectedSeedHash, identity, CoreDemoWorkspaceStatus.ALLOCATING);
    }

    public CleanupInspection inspectCleanupEligibility(CoreDemoWorkspace run) {
        try {
            validateOwned(run);
            return new CleanupInspection(true, "ELIGIBLE");
        } catch (Exception e) {
            return new CleanupInspection(false, "OWNERSHIP_NOT_PROVEN");
        }
    }

    public CoreDemoWorkspace cleanup(CoreDemoWorkspace run) throws IOException {
        if (run != null && run.status() == CoreDemoWorkspaceStatus.CLEANED
                && !Files.exists(run.runRoot(), LinkOption.NOFOLLOW_LINKS)) return run;
        validateOwned(run);
        deleteOwnedTree(run.runRoot());
        return run.withStatus(CoreDemoWorkspaceStatus.CLEANED);
    }

    public CoreDemoWorkspace retainForDiagnostics(CoreDemoWorkspace run) {
        return run.withStatus(CoreDemoWorkspaceStatus.RETAINED_FOR_DIAGNOSTICS);
    }

    /**
     * Removes only runtime workspace/diagnostics after a committed successful bundle.
     * The ownership marker, immutable artifacts and minimal state database remain.
     */
    public CoreDemoWorkspace cleanupSuccessfulRuntime(CoreDemoWorkspace run)
            throws IOException {
        validateOwned(run);
        deleteOwnedComponent(run.runRoot(), run.workspace(), "workspace");
        deleteOwnedComponent(run.runRoot(), run.diagnostics(), "diagnostics");
        return run.withStatus(CoreDemoWorkspaceStatus.RUNTIME_CLEANED);
    }

    private void deleteOwnedComponent(Path runRoot, Path component, String expectedName)
            throws IOException {
        Path normalized = component.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(runRoot)
                || !expectedName.equals(normalized.getFileName().toString())) {
            throw new IOException("Runtime cleanup component mismatch");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)) throw new IOException("Runtime component is linked");
            deleteOwnedTree(normalized);
        }
    }

    private void validateOwned(CoreDemoWorkspace run) throws IOException {
        Objects.requireNonNull(run, "run");
        requireRunId(run.runId());
        Path actual = run.runRoot().toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireContained(actual);
        if (!actual.getFileName().toString().equals(run.runId())) throw new IOException("Run id/path mismatch");
        if (actual.equals(trustedRoot) || (protectedRepository != null && overlaps(actual, protectedRepository)))
            throw new IOException("Protected path cannot be cleaned");
        Path markerPath = actual.resolve(MARKER);
        if (Files.isSymbolicLink(markerPath) || !Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Ownership marker missing");
        Marker marker = json.readValue(Files.readAllBytes(markerPath), Marker.class);
        if (marker.schemaVersion != 1 || !marker.runId.equals(run.runId())
                || !marker.manifestHash.equals(run.manifestHash())
                || !marker.expectedSeedHash.equals(run.expectedSeedHash())
                || !marker.workspaceIdentityHash.equals(run.workspaceIdentityHash())
                || !marker.workspaceIdentityHash.equals(hash("workspace-v1\0"
                    + actual.resolve("workspace").toAbsolutePath().normalize() + "\0"
                    + run.runId() + "\0" + marker.scenarioId + "\0" + run.manifestHash())))
            throw new IOException("Ownership marker mismatch");
        rejectLinks(actual);
    }

    private void deleteOwnedTree(Path root) throws IOException {
        DirectoryNotEmptyException changed = null;
        for (int pass = 1; pass <= MAX_DELETE_PASSES; pass++) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                deleteOwnedTreePass(root, pass);
                return;
            } catch (DirectoryNotEmptyException error) {
                changed = error;
            }
        }
        throw new IOException("Owned tree remained active during bounded cleanup", changed);
    }

    private void deleteOwnedTreePass(Path root, int pass) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || attrs.isOther()) throw new IOException("Refusing to clean linked path");
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw error;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null && !(error instanceof NoSuchFileException)) throw error;
                directoryDeleteObserver.beforeDelete(dir, pass);
                Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    public void assertSafeWorkspace(Path workspace) throws IOException {
        Path normalized = workspace.toAbsolutePath().normalize();
        if (!normalized.startsWith(trustedRoot) || !normalized.getParent().getParent().equals(trustedRoot))
            throw new IOException("Workspace is outside trusted demo root");
        Path runRoot = normalized.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireContained(runRoot); rejectLinks(runRoot);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized))
            throw new IOException("Workspace is a link");
    }

    private void rejectLinks(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) if (Files.isSymbolicLink(path))
                throw new IOException("Owned run contains a symbolic link");
        }
    }
    private void requireContained(Path path) throws IOException {
        if (!path.startsWith(trustedRoot) || path.equals(trustedRoot)) throw new IOException("Path escapes trusted demo root");
    }
    private static Path requireTrustedRoot(Path root) throws IOException {
        Path value = Objects.requireNonNull(root, "trustedRoot").toAbsolutePath().normalize();
        Files.createDirectories(value);
        if (Files.isSymbolicLink(value) || !Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Trusted demo root is unsafe");
        return value.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
    private static void requireRunId(String value) {
        if (value == null || !RUN_ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid demo run id");
    }
    private static boolean overlaps(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public record CleanupInspection(boolean eligible, String category) {}
    @FunctionalInterface
    interface DirectoryDeleteObserver {
        DirectoryDeleteObserver NONE = (directory, pass) -> { };
        void beforeDelete(Path directory, int pass) throws IOException;
    }
    private record Marker(int schemaVersion, String runId, String scenarioId, String manifestHash,
            String expectedSeedHash, String workspaceIdentityHash, String createdAt) {}
    private record RootMarker(int schemaVersion, String rootIdentityHash) {}
}
