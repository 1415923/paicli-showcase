package com.paicli.codeindex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.state.CodeIndexStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CodeIndexer {

    private static final int MAX_ERROR_EXCERPT_CHARS = 2000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JavaSourceDiscovery discovery;
    private final JavaSourceParser parser;
    private final CodeIndexStore store;

    public CodeIndexer(CodeIndexStore store) {
        this(new JavaSourceDiscovery(), new JavaSourceParser(), store);
    }

    public CodeIndexer(JavaSourceDiscovery discovery, JavaSourceParser parser,
                       CodeIndexStore store) {
        this.discovery = discovery;
        this.parser = parser;
        this.store = store;
    }

    public CodeIndexResult index(Path workspaceRoot) throws SQLException {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot is required");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        JavaSourceDiscovery.DiscoveryResult discoveryResult =
                discovery.discover(root);
        List<CodeIndexResult.CodeIndexIssue> issues = new ArrayList<>();
        for (JavaSourceDiscovery.DiscoveryIssue issue :
                discoveryResult.issues()) {
            issues.add(new CodeIndexResult.CodeIndexIssue(
                    issue.relativePath(), issue.reason()));
        }

        Map<String, CodeIndexStore.CodeFileRecord> existingByPath =
                existingJavaFilesByPath();
        Set<String> discoveredPaths = new HashSet<>();
        int indexed = 0;
        int skipped = 0;
        int errors = 0;

        for (JavaSourceDiscovery.JavaSourceFile sourceFile :
                discoveryResult.files()) {
            String relativePath = sourceFile.relativePath();
            discoveredPaths.add(relativePath);
            Path absolutePath = root.resolve(relativePath).normalize();
            try {
                FileMetadata metadata = readMetadata(absolutePath);
                CodeIndexStore.CodeFileRecord existing =
                        existingByPath.get(relativePath);
                if (isUnchanged(existing, metadata)) {
                    skipped++;
                    continue;
                }

                JavaSourceParseResult parseResult =
                        parser.parse(relativePath, metadata.source());
                String fileId = fileId(relativePath);
                if (parseResult.status() == JavaSourceParseResult.ParseStatus.ERROR) {
                    store.markFileError(fileId, relativePath, "java",
                            metadata.sha256(), metadata.mtimeMillis(),
                            metadata.sizeBytes(),
                            excerpt(parseResult.errorExcerpt()));
                    errors++;
                    continue;
                }

                store.replaceFileIndex(
                        fileRecord(fileId, relativePath, metadata, parseResult),
                        classRecords(fileId, parseResult),
                        methodRecords(fileId, parseResult),
                        fieldRecords(fileId, parseResult));
                indexed++;
            } catch (Exception e) {
                String fileId = fileId(relativePath);
                FileMetadata metadata = safeMetadata(absolutePath);
                store.markFileError(fileId, relativePath, "java",
                        metadata.sha256(), metadata.mtimeMillis(),
                        metadata.sizeBytes(), excerpt(e.getMessage()));
                issues.add(new CodeIndexResult.CodeIndexIssue(
                        relativePath, excerpt(e.getMessage())));
                errors++;
            }
        }

        int deleted = markDeletedFiles(existingByPath, discoveredPaths);

        return new CodeIndexResult(
                discoveryResult.files().size(), indexed, skipped, errors,
                deleted, List.copyOf(issues));
    }

    private Map<String, CodeIndexStore.CodeFileRecord> existingJavaFilesByPath()
            throws SQLException {
        Map<String, CodeIndexStore.CodeFileRecord> files = new HashMap<>();
        for (CodeIndexStore.CodeFileRecord file :
                store.findFilesByLanguage("java")) {
            files.put(file.relativePath(), file);
        }
        return files;
    }

    private int markDeletedFiles(
            Map<String, CodeIndexStore.CodeFileRecord> existingByPath,
            Set<String> discoveredPaths) throws SQLException {
        int deleted = 0;
        for (CodeIndexStore.CodeFileRecord existing : existingByPath.values()) {
            if (!discoveredPaths.contains(existing.relativePath())
                    && !CodeIndexStore.STATUS_DELETED.equals(existing.status())) {
                store.markDeleted(existing.fileId());
                deleted++;
            }
        }
        return deleted;
    }

    private boolean isUnchanged(CodeIndexStore.CodeFileRecord existing,
                                FileMetadata metadata) {
        if (existing == null || CodeIndexStore.STATUS_DELETED.equals(existing.status())) {
            return false;
        }
        if (existing.mtimeMillis() == metadata.mtimeMillis()
                && existing.sizeBytes() == metadata.sizeBytes()
                && metadata.sha256().equals(existing.sha256())) {
            return true;
        }
        return metadata.sha256().equals(existing.sha256());
    }

    private FileMetadata readMetadata(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        FileTime mtime = Files.getLastModifiedTime(path);
        return new FileMetadata(
                bytes.length, mtime.toMillis(), sha256(bytes),
                new String(bytes, StandardCharsets.UTF_8));
    }

    private FileMetadata safeMetadata(Path path) {
        try {
            return readMetadata(path);
        } catch (IOException e) {
            return new FileMetadata(0, 0, null, "");
        }
    }

    private CodeIndexStore.CodeFileRecord fileRecord(
            String fileId, String relativePath, FileMetadata metadata,
            JavaSourceParseResult parseResult) throws JsonProcessingException {
        return new CodeIndexStore.CodeFileRecord(
                fileId, relativePath, "java", parseResult.packageName(),
                toJson(parseResult.imports()), metadata.sha256(),
                metadata.mtimeMillis(), metadata.sizeBytes(),
                CodeIndexStore.STATUS_INDEXED, null,
                parseResult.unsupportedNotes().isEmpty()
                        ? null : String.join("\n", parseResult.unsupportedNotes()),
                null);
    }

    private List<CodeIndexStore.ClassRecord> classRecords(
            String fileId, JavaSourceParseResult parseResult)
            throws JsonProcessingException {
        List<CodeIndexStore.ClassRecord> records = new ArrayList<>();
        for (ParsedClass parsedClass : parseResult.classes()) {
            String classId = classId(fileId, parsedClass.qualifiedName());
            records.add(new CodeIndexStore.ClassRecord(
                    classId, fileId, parsedClass.packageName(),
                    parsedClass.className(), parsedClass.qualifiedName(),
                    parsedClass.kind(), parsedClass.startLine(),
                    parsedClass.endLine(), String.join(" ", parsedClass.modifiers()),
                    parsedClass.extendsName(), toJson(parsedClass.implementsNames())));
        }
        return records;
    }

    private List<CodeIndexStore.MethodRecord> methodRecords(
            String fileId, JavaSourceParseResult parseResult)
            throws JsonProcessingException {
        List<CodeIndexStore.MethodRecord> records = new ArrayList<>();
        for (ParsedMethod parsedMethod : parseResult.methods()) {
            String classId = classId(fileId, parsedMethod.qualifiedClassName());
            String methodId = methodId(fileId, parsedMethod);
            records.add(new CodeIndexStore.MethodRecord(
                    methodId, fileId, classId, parsedMethod.methodName(),
                    parsedMethod.kind(), parsedMethod.signature(),
                    parsedMethod.returnType(), toJson(parsedMethod.parameterTypes()),
                    parsedMethod.startLine(), parsedMethod.endLine(),
                    parsedMethod.chunkText(), parsedMethod.chunkSha256(),
                    String.join(" ", parsedMethod.modifiers())));
        }
        return records;
    }

    private List<CodeIndexStore.FieldRecord> fieldRecords(
            String fileId, JavaSourceParseResult parseResult) {
        List<CodeIndexStore.FieldRecord> records = new ArrayList<>();
        for (ParsedField parsedField : parseResult.fields()) {
            String classId = classId(fileId, parsedField.qualifiedClassName());
            String fieldId = fieldId(fileId, parsedField);
            records.add(new CodeIndexStore.FieldRecord(
                    fieldId, fileId, classId, parsedField.fieldName(),
                    parsedField.fieldType(), parsedField.startLine(),
                    parsedField.endLine(), String.join(" ", parsedField.modifiers())));
        }
        return records;
    }

    private String toJson(List<String> values) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(values);
    }

    private String fileId(String relativePath) {
        return "file-" + sha256(relativePath.getBytes(StandardCharsets.UTF_8));
    }

    private String classId(String fileId, String qualifiedName) {
        return "class-" + sha256(
                (fileId + "\n" + qualifiedName).getBytes(StandardCharsets.UTF_8));
    }

    private String methodId(String fileId, ParsedMethod method) {
        return "method-" + sha256((fileId + "\n" + method.qualifiedClassName()
                + "\n" + method.kind() + "\n" + method.signature()
                + "\n" + method.startLine()).getBytes(StandardCharsets.UTF_8));
    }

    private String fieldId(String fileId, ParsedField field) {
        return "field-" + sha256((fileId + "\n" + field.qualifiedClassName()
                + "\n" + field.fieldName() + "\n" + field.startLine())
                .getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String excerpt(String value) {
        if (value == null || value.isBlank()) {
            return "Code indexing failed";
        }
        return value.length() <= MAX_ERROR_EXCERPT_CHARS
                ? value : value.substring(0, MAX_ERROR_EXCERPT_CHARS);
    }

    private record FileMetadata(
            long sizeBytes,
            long mtimeMillis,
            String sha256,
            String source
    ) {
    }
}
