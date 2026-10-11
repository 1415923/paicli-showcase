package com.paicli.skill;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.paicli.config.Config;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.state.SkillLoadStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Discovers and strictly parses one-level workspace skills without executing them. */
public final class SkillLoader {

    public static final int MAX_SKILL_FILE_BYTES = 64 * 1024;
    private static final int MAX_AUDIT_TEXT_BYTES = 2048;
    private static final Pattern VALID_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path workspace;
    private final SkillLoadStore store;
    private final PersistenceSanitizer sanitizer;
    private final ObjectMapper yamlMapper;
    private FencedExecutionGuard executionGuard;

    public void bindExecutionGuard(FencedExecutionGuard guard) { this.executionGuard=guard; }
    SkillLoadStore store() { return store; }
    public Path workspace() { return workspace; }
    public static SkillDefinition inspectPackage(Path directory) throws IOException {
        Path root=SkillPackageFiles.plain(directory);
        try { return new SkillLoader(root,null,new PersistenceSanitizer())
                .parse(root.resolve("SKILL.md"),"SKILL.md","inspect","inspect"); }
        catch(InvalidSkillException invalid){throw new IOException(invalid.getMessage(),invalid);}
    }

    void bindPackage(SkillDefinition skill,SkillPackageFiles.Snapshot snapshot)throws IOException,SQLException {
        String existing=store.packageHash(skill.skillLoadId()).orElse(null);
        if(existing!=null){
            if(!existing.equals(snapshot.hash())) throw new SkillRuntime.SkillRuntimeException("SKILL_PACKAGE_CHANGED: "+skill.name());
            return;
        }
        String manifest=new ObjectMapper().writeValueAsString(snapshot.hashes());
        if(executionGuard!=null) executionGuard.bindSkillPackage(skill.skillLoadId(),snapshot.hash(),manifest);
        else store.bindPackage(skill.skillLoadId(),snapshot.hash(),manifest);
    }

    void recordAccess(SkillLoadStore.Access fact)throws SQLException {
        if(executionGuard!=null)executionGuard.recordSkillAccess(fact);else store.recordAccess(fact);
    }

    public SkillLoader(Path workspace, SkillLoadStore store,
                       PersistenceSanitizer sanitizer) throws IOException {
        this(workspace,store,sanitizer,null);
    }

    public SkillLoader(Path workspace,SkillLoadStore store,PersistenceSanitizer sanitizer,
            FencedExecutionGuard executionGuard)throws IOException{
        this.workspace = Objects.requireNonNull(workspace, "workspace").toRealPath();
        this.store = store; // null only for inert package inspection
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.executionGuard=executionGuard;
        this.yamlMapper = new ObjectMapper(YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public List<SkillDefinition> discover(String taskId, String runId,
                                          List<String> configuredDirs)
            throws IOException, SQLException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(configuredDirs, "configuredDirs");
        Objects.requireNonNull(store,"discovery store");

        List<Path> candidates = discoverCandidates(configuredDirs);
        List<SkillDefinition> definitions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path candidate : candidates) {
            String relativePath = relative(candidate);
            String skillId = sha256(relativePath);
            SkillLoadStore.SkillLoadRecord existing = store
                    .findByRunAndPath(runId, relativePath).orElse(null);
            if (existing != null
                    && !SkillLoadStore.STATUS_LOADED.equals(existing.status())) {
                continue;
            }
            String skillLoadId = existing != null
                    ? existing.skillLoadId() : UUID.randomUUID().toString();
            try {
                SkillDefinition definition = parse(
                        candidate, relativePath, skillId, skillLoadId);
                if(existing!=null&&!definition.contentSha256().equals(existing.contentSha256()))
                    throw new SkillRuntime.SkillRuntimeException("SKILL_CONTENT_CHANGED: "+definition.name());
                String normalizedName = definition.name().toLowerCase(Locale.ROOT);
                if (!names.add(normalizedName)) {
                    recordRejected(skillLoadId, skillId, runId, taskId,
                            definition.name(), relativePath,
                            "Duplicate skill name: " + definition.name());
                    continue;
                }
                if (existing == null) {
                    record(skillLoadId,skillId,runId,taskId,definition.name(),relativePath,
                            sanitizer.sanitizeAndTruncate(definition.description(),
                                    MAX_AUDIT_TEXT_BYTES),definition.contentSha256(),
                            SkillLoadStore.STATUS_LOADED,null);
                }
                definitions.add(definition);
            } catch (InvalidSkillException | SecurityException e) {
                if (existing == null) {
                    recordRejected(skillLoadId, skillId, runId, taskId,
                            null, relativePath, e.getMessage());
                }
            } catch (IOException e) {
                if (existing == null) {
                    record(skillLoadId,skillId,runId,taskId,null,relativePath,null,null,
                            SkillLoadStore.STATUS_ERROR,sanitizer.sanitizeAndTruncate(
                                    "Could not read SKILL.md: "+e.getClass().getSimpleName(),
                                    MAX_AUDIT_TEXT_BYTES));
                }
            }
        }
        return List.copyOf(definitions);
    }

    private List<Path> discoverCandidates(List<String> configuredDirs)
            throws IOException {
        Set<Path> candidates = new HashSet<>();
        Set<Path> roots = new HashSet<>();
        for (String configuredDir : configuredDirs) {
            if (configuredDir == null || configuredDir.isBlank()) {
                throw new IllegalArgumentException(
                        "Skill directories must be non-blank");
            }
            Path root = workspace.resolve(configuredDir).normalize();
            requireInsideWorkspace(root);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            SkillPackageFiles.plain(root);
            Path realRoot = root.toRealPath();
            requireInsideWorkspace(realRoot);
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)
                    || !roots.add(realRoot)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(realRoot)) {
                var iterator=entries.iterator();int count=0;
                while(iterator.hasNext()) {
                    Path entry=iterator.next();
                    if(++count>256)throw new IOException("Skill discovery exceeds 256 directory entries");
                    if (Files.isSymbolicLink(entry)) {
                        candidates.add(entry.resolve("SKILL.md"));
                        continue;
                    }
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    Path skillFile = entry.resolve("SKILL.md");
                    if (Files.exists(skillFile, LinkOption.NOFOLLOW_LINKS)) {
                        candidates.add(skillFile);
                    }
                }
            }
        }
        if(candidates.size()>64)throw new IOException("Skill discovery exceeds 64 packages");
        return candidates.stream()
                .sorted(Comparator.comparing(this::relative))
                .toList();
    }

    private SkillDefinition parse(Path path, String relativePath,
                                  String skillId, String skillLoadId)
            throws IOException, InvalidSkillException {
        SkillPackageFiles.plain(path);
        Path realPath = path.toRealPath();
        requireInsideWorkspace(realPath);
        if (!Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidSkillException("SKILL.md is not a regular file");
        }
        long size = Files.size(realPath);
        if (size > MAX_SKILL_FILE_BYTES) {
            throw new InvalidSkillException("SKILL.md exceeds size limit");
        }
        byte[] bytes = SkillPackageFiles.read(realPath.getParent(),"SKILL.md",MAX_SKILL_FILE_BYTES);
        if (bytes.length > MAX_SKILL_FILE_BYTES) {
            throw new InvalidSkillException("SKILL.md exceeds size limit");
        }
        String source = SkillPackageFiles.text(bytes);
        FrontMatter frontMatter = splitFrontMatter(source);
        SkillMetadata metadata;
        try {
            metadata = yamlMapper.readValue(frontMatter.yaml(), SkillMetadata.class);
        } catch (IOException e) {
            throw new InvalidSkillException("Invalid YAML front matter", e);
        }
        validateMetadata(metadata);
        return new SkillDefinition(
                skillLoadId,
                skillId,
                metadata.name().trim(),
                metadata.description().trim(),
                cleanList(metadata.triggers()),
                cleanList(metadata.allowedTools()),
                metadata.maxContextChars(),
                frontMatter.body(),
                realPath,
                relativePath,
                sha256(bytes)
        );
    }

    private FrontMatter splitFrontMatter(String source)
            throws InvalidSkillException {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            throw new InvalidSkillException(
                    "SKILL.md must start with YAML front matter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new InvalidSkillException(
                    "SKILL.md front matter is not closed");
        }
        return new FrontMatter(
                normalized.substring(4, end),
                normalized.substring(end + 5)
        );
    }

    private void validateMetadata(SkillMetadata metadata)
            throws InvalidSkillException {
        if (metadata == null
                || metadata.name() == null
                || !VALID_NAME.matcher(metadata.name().trim()).matches()) {
            throw new InvalidSkillException(
                    "Skill name is required and must be a safe identifier");
        }
        if (metadata.description() == null || metadata.description().isBlank() || metadata.description().length()>2048) {
            throw new InvalidSkillException("Skill description is required");
        }
        if (metadata.triggers()!=null && cleanList(metadata.triggers()).size() != metadata.triggers().size()) {
            throw new InvalidSkillException(
                    "triggers must contain unique non-blank values");
        }
        if (metadata.allowedTools() != null
                && cleanList(metadata.allowedTools()).size()
                != metadata.allowedTools().size()) {
            throw new InvalidSkillException(
                    "allowed_tools must contain non-blank values");
        }
        if (metadata.maxContextChars() != null
                && (metadata.maxContextChars() < 1
                || metadata.maxContextChars() > Config.MAX_SKILL_CONTEXT_CHARS)) {
            throw new InvalidSkillException(
                    "max_context_chars is outside the supported range");
        }
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private void recordRejected(String loadId, String skillId,
                                String runId, String taskId, String name,
                                String path, String reason) throws SQLException {
        record(loadId,skillId,runId,taskId,name,path,null,null,
                SkillLoadStore.STATUS_REJECTED,
                sanitizer.sanitizeAndTruncate(reason,MAX_AUDIT_TEXT_BYTES));
    }

    private void record(String id,String skillId,String runId,String taskId,String name,String path,
            String description,String hash,String status,String error)throws SQLException{
        if(executionGuard!=null){executionGuard.recordSkillLoad(id,skillId,name,path,description,
                hash,status,error);return;}
        if(SkillLoadStore.STATUS_LOADED.equals(status))store.recordLoaded(id,skillId,runId,taskId,
                name,path,description,hash);
        else if(SkillLoadStore.STATUS_REJECTED.equals(status))store.recordRejected(id,skillId,runId,
                taskId,name,path,error);
        else store.recordError(id,skillId,runId,taskId,name,path,error);
    }

    private void rejectLink(Path path) {
        if (path != null && Files.isSymbolicLink(path)) {
            throw new SecurityException("Skill path is a symbolic link");
        }
    }

    private void requireInsideWorkspace(Path path) {
        if (!path.startsWith(workspace)) {
            throw new SecurityException("Skill path is outside workspace");
        }
    }

    private String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) {
            return normalized.toString().replace('\\', '/');
        }
        return workspace.relativize(normalized).toString().replace('\\', '/');
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record FrontMatter(String yaml, String body) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    private record SkillMetadata(
            String name,
            String description,
            List<String> triggers,
            @com.fasterxml.jackson.annotation.JsonAlias("allowed-tools")
            @com.fasterxml.jackson.annotation.JsonFormat(with=com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<String> allowed_tools,
            Integer max_context_chars
    ) {
        List<String> allowedTools() {
            return allowed_tools;
        }

        Integer maxContextChars() {
            return max_context_chars;
        }
    }

    private static final class InvalidSkillException extends Exception {
        private InvalidSkillException(String message) {
            super(message);
        }

        private InvalidSkillException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
