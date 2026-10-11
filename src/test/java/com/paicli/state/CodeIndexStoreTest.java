package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIndexStoreTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void storesCodeFileWithPackageAndImportsJson() throws Exception {
        stateStore.codeIndex.upsertFile(file("file-app", "src/main/java/demo/App.java"));

        CodeIndexStore.CodeFileRecord record = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();

        assertEquals("file-app", record.fileId());
        assertEquals("java", record.language());
        assertEquals("demo", record.packageName());
        assertEquals("[\"java.util.List\",\"java.util.Map\"]", record.importsJson());
        assertEquals(CodeIndexStore.STATUS_INDEXED, record.status());
        assertEquals(1, stateStore.codeIndex.countFilesByStatus(
                CodeIndexStore.STATUS_INDEXED));
    }

    @Test
    void replacesAndReadsClassMethodAndFieldRows() throws Exception {
        CodeIndexStore.CodeFileRecord file =
                file("file-service", "src/main/java/demo/Service.java");
        CodeIndexStore.ClassRecord clazz = new CodeIndexStore.ClassRecord(
                "class-service", "file-service", "demo", "Service",
                "demo.Service", CodeIndexStore.CLASS_KIND_CLASS, 4, 25,
                "public", "BaseService", "[\"Runnable\"]");
        CodeIndexStore.MethodRecord constructor = new CodeIndexStore.MethodRecord(
                "method-ctor", "file-service", "class-service", "Service",
                CodeIndexStore.METHOD_KIND_CONSTRUCTOR, "Service()",
                null, "[]", 7, 9,
                "public Service() {\n}", "sha-ctor", "public");
        CodeIndexStore.MethodRecord method = new CodeIndexStore.MethodRecord(
                "method-run", "file-service", "class-service", "run",
                CodeIndexStore.METHOD_KIND_METHOD, "void run(String input)",
                "void", "[\"String\"]", 11, 15,
                "public void run(String input) {\n    System.out.println(input);\n}",
                "sha-run", "public");
        CodeIndexStore.FieldRecord field = new CodeIndexStore.FieldRecord(
                "field-name", "file-service", "class-service", "name",
                "String", 5, 5, "private final");

        stateStore.codeIndex.replaceFileIndex(
                file, List.of(clazz), List.of(constructor, method), List.of(field));

        List<CodeIndexStore.ClassRecord> classes =
                stateStore.codeIndex.findClassesByFile("file-service");
        List<CodeIndexStore.MethodRecord> methods =
                stateStore.codeIndex.findMethodsByFile("file-service");
        List<CodeIndexStore.FieldRecord> fields =
                stateStore.codeIndex.findFieldsByFile("file-service");

        assertEquals(1, classes.size());
        assertEquals("demo.Service", classes.getFirst().qualifiedName());
        assertEquals("[\"Runnable\"]", classes.getFirst().implementsNames());
        assertEquals(2, methods.size());
        assertEquals(CodeIndexStore.METHOD_KIND_CONSTRUCTOR,
                methods.getFirst().kind());
        assertEquals("void run(String input)", methods.getLast().signature());
        assertTrue(methods.getLast().chunkText().contains("System.out.println"));
        assertEquals("sha-run", methods.getLast().chunkSha256());
        assertEquals(1, fields.size());
        assertEquals("name", fields.getFirst().fieldName());
    }

    @Test
    void clearIndexRowsRemovesOnlyRowsForSelectedFile() throws Exception {
        stateStore.codeIndex.replaceFileIndex(
                file("file-one", "src/main/java/demo/One.java"),
                List.of(classRecord("class-one", "file-one", "One")),
                List.of(methodRecord("method-one", "file-one", "class-one", "one")),
                List.of(fieldRecord("field-one", "file-one", "class-one", "one")));
        stateStore.codeIndex.replaceFileIndex(
                file("file-two", "src/main/java/demo/Two.java"),
                List.of(classRecord("class-two", "file-two", "Two")),
                List.of(methodRecord("method-two", "file-two", "class-two", "two")),
                List.of(fieldRecord("field-two", "file-two", "class-two", "two")));

        stateStore.codeIndex.clearIndexRows("file-one");

        assertTrue(stateStore.codeIndex.findClassesByFile("file-one").isEmpty());
        assertTrue(stateStore.codeIndex.findMethodsByFile("file-one").isEmpty());
        assertTrue(stateStore.codeIndex.findFieldsByFile("file-one").isEmpty());
        assertEquals(1, stateStore.codeIndex.findClassesByFile("file-two").size());
        assertEquals(1, stateStore.codeIndex.findMethodsByFile("file-two").size());
        assertEquals(1, stateStore.codeIndex.findFieldsByFile("file-two").size());
    }

    @Test
    void parseFailureStatusAndErrorExcerptRoundTrip() throws Exception {
        stateStore.codeIndex.markFileError(
                "file-bad", "src/main/java/demo/Broken.java", "java",
                "sha-bad", 123L, 456L, "Parse error near line 3");

        CodeIndexStore.CodeFileRecord record = stateStore.codeIndex
                .findFileById("file-bad").orElseThrow();

        assertEquals(CodeIndexStore.STATUS_ERROR, record.status());
        assertEquals("Parse error near line 3", record.errorExcerpt());
        assertTrue(stateStore.codeIndex.findClassesByFile("file-bad").isEmpty());
        assertEquals(1, stateStore.codeIndex.countFilesByStatus(
                CodeIndexStore.STATUS_ERROR));
    }

    @Test
    void queryApiReturnsClassCandidatesAndQualifiedClass() throws Exception {
        stateStore.codeIndex.replaceFileIndex(
                file("file-demo-service", "src/main/java/demo/Service.java"),
                List.of(classRecord("class-demo-service", "file-demo-service", "Service")),
                List.of(), List.of());
        stateStore.codeIndex.replaceFileIndex(
                file("file-other-service", "src/main/java/other/Service.java"),
                List.of(new CodeIndexStore.ClassRecord(
                        "class-other-service", "file-other-service", "other",
                        "Service", "other.Service", CodeIndexStore.CLASS_KIND_CLASS,
                        3, 12, "public", null, "[]")),
                List.of(), List.of());

        List<CodeIndexStore.ClassQueryRecord> candidates =
                stateStore.codeIndex.findClassesBySimpleName("Service");
        CodeIndexStore.ClassQueryRecord exact = stateStore.codeIndex
                .findClassByQualifiedName("demo.Service").orElseThrow();

        assertEquals(2, candidates.size());
        assertEquals("src/main/java/demo/Service.java", candidates.getFirst().relativePath());
        assertEquals("src/main/java/other/Service.java", candidates.getLast().relativePath());
        assertEquals("demo.Service", exact.qualifiedName());
        assertEquals("src/main/java/demo/Service.java", exact.relativePath());
        assertEquals("[\"java.util.List\",\"java.util.Map\"]", exact.importsJson());
    }

    @Test
    void queryApiReturnsOverloadedMethodsWithoutFolding() throws Exception {
        CodeIndexStore.CodeFileRecord file =
                file("file-service", "src/main/java/demo/Service.java");
        CodeIndexStore.ClassRecord clazz =
                classRecord("class-service", "file-service", "Service");
        CodeIndexStore.MethodRecord noArg = methodRecord(
                "method-run-noarg", "file-service", "class-service", "run");
        CodeIndexStore.MethodRecord withArg = new CodeIndexStore.MethodRecord(
                "method-run-string", "file-service", "class-service", "run",
                CodeIndexStore.METHOD_KIND_METHOD, "void run(String input)",
                "void", "[\"String\"]", 8, 10,
                "void run(String input) {\n    System.out.println(input);\n}",
                "sha-method-run-string", "public");
        stateStore.codeIndex.replaceFileIndex(
                file, List.of(clazz), List.of(noArg, withArg), List.of());

        List<CodeIndexStore.MethodQueryRecord> byName =
                stateStore.codeIndex.findMethodsByName("run");
        List<CodeIndexStore.MethodQueryRecord> byClass =
                stateStore.codeIndex.findMethodsByClassAndName("class-service", "run");
        List<CodeIndexStore.MethodQueryRecord> byQualifiedClass =
                stateStore.codeIndex.findMethodsByQualifiedClassAndName("demo.Service", "run");

        assertEquals(2, byName.size());
        assertEquals(2, byClass.size());
        assertEquals(2, byQualifiedClass.size());
        assertEquals("void run()", byName.getFirst().signature());
        assertEquals("void run(String input)", byName.getLast().signature());
        assertEquals("src/main/java/demo/Service.java", byName.getLast().relativePath());
        assertEquals(8, byName.getLast().startLine());
        assertEquals("sha-method-run-string", byName.getLast().chunkSha256());
        assertTrue(byName.getLast().chunkText().contains("System.out.println"));
    }

    @Test
    void queryApiReturnsFieldsByClassAndName() throws Exception {
        stateStore.codeIndex.replaceFileIndex(
                file("file-service", "src/main/java/demo/Service.java"),
                List.of(classRecord("class-service", "file-service", "Service")),
                List.of(),
                List.of(fieldRecord("field-name", "file-service", "class-service", "name"),
                        fieldRecord("field-count", "file-service", "class-service", "count")));

        List<CodeIndexStore.FieldQueryRecord> fields =
                stateStore.codeIndex.findFieldsByClass("class-service");
        List<CodeIndexStore.FieldQueryRecord> named =
                stateStore.codeIndex.findFieldsByClassAndName("class-service", "name");

        assertEquals(2, fields.size());
        assertEquals(1, named.size());
        assertEquals("name", named.getFirst().fieldName());
        assertEquals("demo.Service", named.getFirst().qualifiedName());
        assertEquals("src/main/java/demo/Service.java", named.getFirst().relativePath());
        assertEquals(5, named.getFirst().startLine());
    }

    @Test
    void queryApiExcludesErrorDeletedAndSkippedFilesByDefault() throws Exception {
        stateStore.codeIndex.replaceFileIndex(
                file("file-good", "src/main/java/demo/Good.java"),
                List.of(classRecord("class-good", "file-good", "Target")),
                List.of(methodRecord("method-good", "file-good", "class-good", "find")),
                List.of(fieldRecord("field-good", "file-good", "class-good", "value")));
        stateStore.codeIndex.replaceFileIndex(
                fileWithStatus("file-error", "src/main/java/demo/ErrorTarget.java",
                        CodeIndexStore.STATUS_ERROR),
                List.of(classRecord("class-error", "file-error", "Target")),
                List.of(methodRecord("method-error", "file-error", "class-error", "find")),
                List.of(fieldRecord("field-error", "file-error", "class-error", "value")));
        stateStore.codeIndex.replaceFileIndex(
                fileWithStatus("file-skipped", "src/main/java/demo/SkippedTarget.java",
                        CodeIndexStore.STATUS_SKIPPED),
                List.of(classRecord("class-skipped", "file-skipped", "Target")),
                List.of(methodRecord("method-skipped", "file-skipped", "class-skipped", "find")),
                List.of(fieldRecord("field-skipped", "file-skipped", "class-skipped", "value")));
        stateStore.codeIndex.replaceFileIndex(
                fileWithStatus("file-deleted", "src/main/java/demo/DeletedTarget.java",
                        CodeIndexStore.STATUS_INDEXED),
                List.of(classRecord("class-deleted", "file-deleted", "Target")),
                List.of(methodRecord("method-deleted", "file-deleted", "class-deleted", "find")),
                List.of(fieldRecord("field-deleted", "file-deleted", "class-deleted", "value")));
        stateStore.codeIndex.markDeleted("file-deleted");

        assertEquals(1, stateStore.codeIndex.findClassesBySimpleName("Target").size());
        assertEquals(1, stateStore.codeIndex.findMethodsByName("find").size());
        assertEquals(1, stateStore.codeIndex
                .findFieldsByClassAndName("class-good", "value").size());
        assertTrue(stateStore.codeIndex
                .findFieldsByClassAndName("class-error", "value").isEmpty());
    }

    private CodeIndexStore.CodeFileRecord file(String fileId, String path) {
        return new CodeIndexStore.CodeFileRecord(
                fileId, path, "java", "demo",
                "[\"java.util.List\",\"java.util.Map\"]",
                "sha-" + fileId, 100L, 200L,
                CodeIndexStore.STATUS_INDEXED, null, null, null);
    }

    private CodeIndexStore.CodeFileRecord fileWithStatus(
            String fileId, String path, String status) {
        return new CodeIndexStore.CodeFileRecord(
                fileId, path, "java", "demo", "[]",
                "sha-" + fileId, 100L, 200L,
                status, null, null, null);
    }

    private CodeIndexStore.ClassRecord classRecord(
            String classId, String fileId, String name) {
        return new CodeIndexStore.ClassRecord(
                classId, fileId, "demo", name, "demo." + name,
                CodeIndexStore.CLASS_KIND_CLASS, 1, 10,
                "public", null, "[]");
    }

    private CodeIndexStore.MethodRecord methodRecord(
            String methodId, String fileId, String classId, String name) {
        return new CodeIndexStore.MethodRecord(
                methodId, fileId, classId, name,
                CodeIndexStore.METHOD_KIND_METHOD, "void " + name + "()",
                "void", "[]", 2, 4, "void " + name + "() {}",
                "sha-" + methodId, "public");
    }

    private CodeIndexStore.FieldRecord fieldRecord(
            String fieldId, String fileId, String classId, String name) {
        return new CodeIndexStore.FieldRecord(
                fieldId, fileId, classId, name, "String",
                5, 5, "private");
    }
}
