package com.paicli.demo.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class CoreDemoFixtureExtractor {
    private final CoreDemoFixtureManifestLoader loader;
    public CoreDemoFixtureExtractor(CoreDemoFixtureManifestLoader loader) { this.loader = loader; }

    public CoreDemoTreeHasher.TreeSnapshot extract(Path workspace,
            CoreDemoFixtureManifestLoader.LoadedManifest loaded) throws CoreDemoFixtureException {
        try {
            if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS))
                throw new CoreDemoFixtureException("Demo workspace already exists");
            Files.createDirectory(workspace);
            Path root = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
            for (CoreDemoFixtureFile file : loaded.manifest().files()) {
                Path target = root.resolve(file.relativePath()).normalize();
                if (!target.startsWith(root)) throw new CoreDemoFixtureException("Fixture target escapes workspace");
                createSafeParents(root, target.getParent());
                byte[] bytes = loader.readSeedFile(file);
                Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
                byte[] written = Files.readAllBytes(target);
                if (written.length != file.sizeBytes()
                        || !CoreDemoFixtureManifestLoader.sha256(written).equals(file.sha256()))
                    throw new CoreDemoFixtureException("Extracted fixture file failed verification");
            }
            CoreDemoTreeHasher.TreeSnapshot snapshot = CoreDemoTreeHasher.hashWorkspace(root, loaded.manifest());
            if (!snapshot.aggregateHash().equals(loaded.manifest().expectedBaselineTreeHash()))
                throw new CoreDemoFixtureException("Extracted fixture tree hash mismatch");
            return snapshot;
        } catch (CoreDemoFixtureException e) {
            throw e;
        } catch (IOException e) {
            throw new CoreDemoFixtureException("Fixture extraction failed", e);
        }
    }

    private void createSafeParents(Path root, Path parent) throws IOException, CoreDemoFixtureException {
        if (parent == null || !parent.normalize().startsWith(root)) throw new CoreDemoFixtureException("Unsafe fixture parent");
        Path relative = root.relativize(parent);
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new CoreDemoFixtureException("Unsafe fixture parent component");
            } else {
                Files.createDirectory(current);
            }
        }
    }
}
