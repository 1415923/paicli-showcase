package com.paicli.demo.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class CoreDemoTreeHasher {
    private CoreDemoTreeHasher() {}

    public static TreeSnapshot hashWorkspace(Path workspace, CoreDemoFixtureManifest manifest)
            throws CoreDemoFixtureException {
        try {
            Path root = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
                throw new CoreDemoFixtureException("Workspace is not a regular directory");
            List<FileFact> facts;
            try {
                facts = manifest.files().stream().map(file -> inspect(root, file)).toList();
            } catch (TreeHashFailure failure) {
                if (failure.getCause() instanceof CoreDemoFixtureException fixture) throw fixture;
                throw failure;
            }
            detectExtraControlledFiles(root, facts);
            return new TreeSnapshot(hashFacts(manifest, facts), facts);
        } catch (CoreDemoFixtureException e) {
            throw e;
        } catch (IOException e) {
            throw new CoreDemoFixtureException("Workspace tree could not be hashed", e);
        }
    }

    static String hashManifestFiles(CoreDemoFixtureManifest manifest) {
        List<FileFact> facts = manifest.files().stream()
                .map(f -> new FileFact(f.relativePath(), f.role(), f.sizeBytes(), f.sha256()))
                .toList();
        return hashFacts(manifest, facts);
    }

    private static FileFact inspect(Path root, CoreDemoFixtureFile file) {
        try {
            Path target = root.resolve(file.relativePath()).normalize();
            if (!target.startsWith(root) || Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new CoreDemoFixtureException("Controlled fixture file is missing or unsafe: " + file.relativePath());
            byte[] bytes = Files.readAllBytes(target);
            return new FileFact(file.relativePath(), file.role(), bytes.length,
                    CoreDemoFixtureManifestLoader.sha256(bytes));
        } catch (IOException | CoreDemoFixtureException e) {
            throw new TreeHashFailure(e);
        }
    }

    private static void detectExtraControlledFiles(Path root, List<FileFact> facts)
            throws IOException, CoreDemoFixtureException {
        Set<String> expected = facts.stream().map(FileFact::relativePath).collect(Collectors.toSet());
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new CoreDemoFixtureException("Workspace contains a symbolic link");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (isControlled(relative) && !expected.contains(relative))
                    throw new CoreDemoFixtureException("Workspace contains an unmanifested controlled file: " + relative);
            }
        }
    }

    private static boolean isControlled(String relative) {
        return relative.equals("pom.xml") || relative.startsWith("src/main/") || relative.startsWith("src/test/");
    }

    private static String hashFacts(CoreDemoFixtureManifest manifest, List<FileFact> facts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "core-demo-tree-v1");
            update(digest, manifest.scenarioId());
            facts.stream().sorted(Comparator.comparing(f -> f.relativePath().getBytes(StandardCharsets.UTF_8),
                    CoreDemoTreeHasher::compareBytes)).forEach(f -> {
                update(digest, f.relativePath()); update(digest, f.role().name());
                update(digest, Long.toString(f.sizeBytes())); update(digest, f.sha256());
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static int compareBytes(byte[] left, byte[] right) {
        return java.util.Arrays.compareUnsigned(left, right);
    }
    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }

    public record FileFact(String relativePath, CoreDemoFixtureFile.Role role, long sizeBytes, String sha256) {}
    public record TreeSnapshot(String aggregateHash, List<FileFact> files) {
        public TreeSnapshot { files = List.copyOf(files); }
    }
    private static final class TreeHashFailure extends RuntimeException {
        TreeHashFailure(Throwable cause) { super(cause); }
    }
}
