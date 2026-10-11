package com.paicli.demo.fixture;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class CoreDemoFixtureManifestLoader {
    public static final String ROOT = "demo-fixtures/core-java-demo/";
    public static final String MANIFEST_RESOURCE = ROOT + "core-demo-manifest.json";
    public static final String SEED_ROOT = ROOT + "seed/";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final ClassLoader resources;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CoreDemoFixtureManifestLoader() {
        this(CoreDemoFixtureManifestLoader.class.getClassLoader());
    }

    public CoreDemoFixtureManifestLoader(ClassLoader resources) {
        this.resources = resources;
    }

    public LoadedManifest load() throws CoreDemoFixtureException {
        byte[] bytes = readResource(MANIFEST_RESOURCE);
        try {
            CoreDemoFixtureManifest manifest = json.readValue(bytes, CoreDemoFixtureManifest.class);
            validate(manifest);
            validateNoExtraResources(manifest);
            for (CoreDemoFixtureFile file : manifest.files()) {
                byte[] content = readResource(SEED_ROOT + file.relativePath());
                if (content.length != file.sizeBytes() || !sha256(content).equals(file.sha256())) {
                    throw new CoreDemoFixtureException("Fixture resource does not match manifest: " + file.relativePath());
                }
            }
            String treeHash = CoreDemoTreeHasher.hashManifestFiles(manifest);
            if (!treeHash.equals(manifest.expectedBaselineTreeHash())) {
                throw new CoreDemoFixtureException("Fixture aggregate hash does not match manifest");
            }
            return new LoadedManifest(manifest, sha256(bytes), bytes.clone());
        } catch (CoreDemoFixtureException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new CoreDemoFixtureException("Fixture manifest is invalid", e);
        }
    }


    private void validateNoExtraResources(CoreDemoFixtureManifest manifest) throws CoreDemoFixtureException {
        try {
            URL manifestUrl = resources.getResource(MANIFEST_RESOURCE);
            if (manifestUrl == null) throw new CoreDemoFixtureException("Fixture manifest resource is missing");
            Set<String> actual = new HashSet<>();
            if ("file".equalsIgnoreCase(manifestUrl.getProtocol())) {
                Path root = Path.of(manifestUrl.toURI()).getParent().resolve("seed");
                try (var paths = Files.walk(root)) {
                    for (Path file : paths.filter(Files::isRegularFile).toList())
                        actual.add(root.relativize(file).toString().replace('\\', '/'));
                }
            } else if ("jar".equalsIgnoreCase(manifestUrl.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) manifestUrl.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    jar.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(SEED_ROOT))
                            .forEach(e -> actual.add(e.getName().substring(SEED_ROOT.length())));
                }
            } else {
                return; // Custom class loaders may not expose enumeration; per-file hashes still apply.
            }
            Set<String> expected = manifest.files().stream().map(CoreDemoFixtureFile::relativePath)
                    .collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected)) throw new CoreDemoFixtureException("Fixture resources do not exactly match manifest");
        } catch (CoreDemoFixtureException e) {
            throw e;
        } catch (Exception e) {
            throw new CoreDemoFixtureException("Fixture resource inventory could not be verified", e);
        }
    }

    public byte[] readSeedFile(CoreDemoFixtureFile file) throws CoreDemoFixtureException {
        return readResource(SEED_ROOT + file.relativePath());
    }

    private byte[] readResource(String name) throws CoreDemoFixtureException {
        try (InputStream input = resources.getResourceAsStream(name)) {
            if (input == null) throw new CoreDemoFixtureException("Required fixture resource is missing: " + name);
            return input.readAllBytes();
        } catch (IOException e) {
            throw new CoreDemoFixtureException("Fixture resource could not be read: " + name, e);
        }
    }

    static void validate(CoreDemoFixtureManifest m) throws CoreDemoFixtureException {
        if (m == null || m.manifestSchemaVersion() != 1) throw new CoreDemoFixtureException("Unsupported fixture manifest schema");
        if (!ID.matcher(text(m.scenarioId())).matches()) throw new CoreDemoFixtureException("Invalid scenarioId");
        if (text(m.fixtureVersion()).length() > 32 || text(m.displayName()).length() > 128)
            throw new CoreDemoFixtureException("Invalid fixture metadata");
        if (m.javaRelease() != 21 || !"MAVEN_OFFLINE_TEST_V1".equals(m.baselineCommandId())
                || m.expectedBaselineTestCount() != 3 || !SHA256.matcher(text(m.expectedBaselineTreeHash())).matches())
            throw new CoreDemoFixtureException("Invalid fixture execution contract");
        List<CoreDemoFixtureFile> files = List.copyOf(m.files() == null ? List.of() : m.files());
        if (files.isEmpty() || files.size() > 64) throw new CoreDemoFixtureException("Invalid fixture file count");
        Set<String> paths = new HashSet<>();
        Set<String> mutable = new HashSet<>();
        for (CoreDemoFixtureFile f : files) {
            validateRelativePath(f.relativePath());
            if (!paths.add(f.relativePath()) || f.sizeBytes() < 0 || f.sizeBytes() > 1_048_576
                    || !SHA256.matcher(text(f.sha256())).matches() || f.role() == null)
                throw new CoreDemoFixtureException("Invalid fixture file entry");
            if (f.role() == CoreDemoFixtureFile.Role.MUTABLE_SOURCE) mutable.add(f.relativePath());
            if ("pom.xml".equals(f.relativePath()) && f.role() != CoreDemoFixtureFile.Role.BUILD_DESCRIPTOR)
                throw new CoreDemoFixtureException("pom.xml must be immutable");
            if (f.relativePath().startsWith("src/test/") && f.role() != CoreDemoFixtureFile.Role.IMMUTABLE_TEST)
                throw new CoreDemoFixtureException("Tests must be immutable");
        }
        List<String> allowed = List.copyOf(m.allowedMutablePaths() == null ? List.of() : m.allowedMutablePaths());
        for (String path : allowed) validateRelativePath(path);
        if (new HashSet<>(allowed).size() != allowed.size() || !mutable.equals(new HashSet<>(allowed)))
            throw new CoreDemoFixtureException("Mutable path allowlist must exactly match mutable files");
    }

    public static void validateRelativePath(String path) throws CoreDemoFixtureException {
        if (path == null || path.isBlank() || path.length() > 240 || path.indexOf('\\') >= 0
                || path.startsWith("/") || path.startsWith("//") || path.contains(":")
                || path.equals(".") || path.contains("/../") || path.startsWith("../") || path.endsWith("/.."))
            throw new CoreDemoFixtureException("Unsafe fixture relative path");
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i)))
            throw new CoreDemoFixtureException("Unsafe fixture relative path");
        for (String segment : path.split("/")) if (segment.isBlank() || segment.equals(".") || segment.equals(".."))
            throw new CoreDemoFixtureException("Unsafe fixture relative path");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String text(String value) { return value == null ? "" : value; }

    public record LoadedManifest(CoreDemoFixtureManifest manifest, String manifestHash, byte[] manifestBytes) {
        public LoadedManifest { manifestBytes = manifestBytes.clone(); }
        @Override public byte[] manifestBytes() { return manifestBytes.clone(); }
    }
}
