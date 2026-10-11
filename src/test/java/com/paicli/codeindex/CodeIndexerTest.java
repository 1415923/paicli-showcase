package com.paicli.codeindex;

import com.paicli.state.CodeIndexStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIndexerTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;
    private CodeIndexer indexer;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
        indexer = new CodeIndexer(stateStore.codeIndex);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void indexesJavaFilesIntoCodeIndexStore() throws Exception {
        write("src/main/java/demo/App.java", """
                package demo;

                import java.util.List;

                public class App {
                    private String name;

                    public App(String name) {
                        this.name = name;
                    }

                    public String hello(List<String> values) {
                        return name + values.size();
                    }
                }
                """);
        write("src/main/java/demo/Worker.java", """
                package demo;

                interface Worker {
                    void run();
                }
                """);

        CodeIndexResult result = indexer.index(tempDir);

        assertEquals(2, result.discoveredCount());
        assertEquals(2, result.indexedCount());
        assertEquals(0, result.errorCount());

        CodeIndexStore.CodeFileRecord app = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();
        assertEquals(CodeIndexStore.STATUS_INDEXED, app.status());
        assertEquals("demo", app.packageName());
        assertEquals("[\"java.util.List\"]", app.importsJson());

        List<CodeIndexStore.ClassRecord> classes =
                stateStore.codeIndex.findClassesByFile(app.fileId());
        List<CodeIndexStore.MethodRecord> methods =
                stateStore.codeIndex.findMethodsByFile(app.fileId());
        List<CodeIndexStore.FieldRecord> fields =
                stateStore.codeIndex.findFieldsByFile(app.fileId());

        assertEquals(1, classes.size());
        assertEquals("demo.App", classes.getFirst().qualifiedName());
        assertEquals(2, methods.size());
        assertTrue(methods.stream().anyMatch(method ->
                method.kind().equals(CodeIndexStore.METHOD_KIND_CONSTRUCTOR)
                        && method.chunkText().contains("this.name = name;")));
        CodeIndexStore.MethodRecord hello = methods.stream()
                .filter(method -> method.methodName().equals("hello"))
                .findFirst().orElseThrow();
        assertEquals("String hello(List<String> values)", hello.signature());
        assertEquals("[\"List<String>\"]", hello.parameterTypes());
        assertEquals(sha256(hello.chunkText()), hello.chunkSha256());
        assertEquals(1, fields.size());
        assertEquals("name", fields.getFirst().fieldName());
    }

    @Test
    void skipsUnchangedFilesWithoutDuplicatingIndexRows() throws Exception {
        write("src/main/java/demo/App.java", simpleClass("App", "one"));

        CodeIndexResult first = indexer.index(tempDir);
        CodeIndexResult second = indexer.index(tempDir);

        CodeIndexStore.CodeFileRecord app = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();
        assertEquals(1, first.indexedCount());
        assertEquals(1, second.skippedUnchangedCount());
        assertEquals(1, stateStore.codeIndex.findClassesByFile(app.fileId()).size());
        assertEquals(1, stateStore.codeIndex.findMethodsByFile(app.fileId()).size());
    }

    @Test
    void reindexesChangedFileAndClearsOldRows() throws Exception {
        write("src/main/java/demo/App.java", simpleClass("App", "oldName"));
        indexer.index(tempDir);
        CodeIndexStore.CodeFileRecord before = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();

        write("src/main/java/demo/App.java", simpleClass("App", "newName"));
        CodeIndexResult result = indexer.index(tempDir);

        CodeIndexStore.CodeFileRecord after = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();
        List<CodeIndexStore.MethodRecord> methods =
                stateStore.codeIndex.findMethodsByFile(after.fileId());

        assertEquals(1, result.indexedCount());
        assertFalse(before.sha256().equals(after.sha256()));
        assertEquals(1, methods.size());
        assertEquals("newName", methods.getFirst().methodName());
        assertFalse(methods.stream().anyMatch(method ->
                method.methodName().equals("oldName")));
    }

    @Test
    void mtimeChangedButSameShaIsSkippedAsUnchanged() throws Exception {
        Path path = write("src/main/java/demo/App.java",
                simpleClass("App", "same"));
        indexer.index(tempDir);

        Files.setLastModifiedTime(path, FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 10_000));
        CodeIndexResult result = indexer.index(tempDir);

        CodeIndexStore.CodeFileRecord app = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();
        assertEquals(1, result.skippedUnchangedCount());
        assertEquals(1, stateStore.codeIndex.findMethodsByFile(app.fileId()).size());
    }

    @Test
    void parseFailureDoesNotBlockOtherFilesAndClearsStaleRows()
            throws Exception {
        write("src/main/java/demo/Broken.java", simpleClass("Broken", "ok"));
        indexer.index(tempDir);

        write("src/main/java/demo/Good.java", simpleClass("Good", "ok"));
        write("src/main/java/demo/Broken.java",
                "package demo;\npublic class Broken {\n void bad( {\n");
        CodeIndexResult result = indexer.index(tempDir);

        CodeIndexStore.CodeFileRecord good = stateStore.codeIndex
                .findFile("src/main/java/demo/Good.java").orElseThrow();
        CodeIndexStore.CodeFileRecord broken = stateStore.codeIndex
                .findFile("src/main/java/demo/Broken.java").orElseThrow();

        assertEquals(1, result.indexedCount());
        assertEquals(1, result.errorCount());
        assertEquals(CodeIndexStore.STATUS_INDEXED, good.status());
        assertEquals(CodeIndexStore.STATUS_ERROR, broken.status());
        assertFalse(broken.errorExcerpt().isBlank());
        assertTrue(stateStore.codeIndex.findClassesByFile(broken.fileId()).isEmpty());
        assertTrue(stateStore.codeIndex.findMethodsByFile(broken.fileId()).isEmpty());
        assertTrue(stateStore.codeIndex.findFieldsByFile(broken.fileId()).isEmpty());
    }

    @Test
    void deletedFileIsMarkedDeletedAndIndexRowsAreCleared() throws Exception {
        Path source = write("src/main/java/demo/App.java",
                simpleClass("App", "one"));
        indexer.index(tempDir);
        CodeIndexStore.CodeFileRecord before = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();

        Files.delete(source);
        CodeIndexResult result = indexer.index(tempDir);

        CodeIndexStore.CodeFileRecord after = stateStore.codeIndex
                .findFile("src/main/java/demo/App.java").orElseThrow();
        assertEquals(1, result.deletedCount());
        assertEquals(CodeIndexStore.STATUS_DELETED, after.status());
        assertEquals(before.fileId(), after.fileId());
        assertTrue(stateStore.codeIndex.findClassesByFile(after.fileId()).isEmpty());
        assertTrue(stateStore.codeIndex.findMethodsByFile(after.fileId()).isEmpty());
        assertTrue(stateStore.codeIndex.findFieldsByFile(after.fileId()).isEmpty());
    }

    @Test
    void excludedDiscoveryDirectoriesDoNotEnterIndex() throws Exception {
        write("target/generated/Generated.java", simpleClass("Generated", "run"));
        write("build/generated/BuildGenerated.java",
                simpleClass("BuildGenerated", "run"));
        write(".paicli/runtime/RuntimeGenerated.java",
                simpleClass("RuntimeGenerated", "run"));

        CodeIndexResult result = indexer.index(tempDir);

        assertEquals(0, result.discoveredCount());
        assertTrue(stateStore.codeIndex.findFilesByLanguage("java").isEmpty());
    }

    @Test
    void indexesComplexJavaFixtureMetadataAndOverloads() throws Exception {
        write("src/main/java/advanced/AdvancedBox.java", advancedFixtureSource());

        CodeIndexResult result = indexer.index(tempDir);

        assertEquals(1, result.discoveredCount());
        assertEquals(1, result.indexedCount());
        CodeIndexStore.CodeFileRecord file = stateStore.codeIndex
                .findFile("src/main/java/advanced/AdvancedBox.java")
                .orElseThrow();
        assertEquals("advanced", file.packageName());
        assertEquals("[\"java.io.Serializable\",\"java.util.List\","
                + "\"java.util.Map\",\"static java.util.Comparator.comparing\"]",
                file.importsJson());

        List<CodeIndexStore.ClassRecord> classes =
                stateStore.codeIndex.findClassesByFile(file.fileId());
        assertTrue(classes.stream().anyMatch(clazz ->
                clazz.className().equals("AdvancedBox")
                        && clazz.kind().equals(CodeIndexStore.CLASS_KIND_CLASS)
                        && clazz.extendsName().equals("BaseBox")
                        && clazz.implementsNames()
                        .equals("[\"Contract\",\"Serializable\"]")));
        assertTrue(classes.stream().anyMatch(clazz ->
                clazz.className().equals("Point")
                        && clazz.kind().equals(CodeIndexStore.CLASS_KIND_RECORD)));
        assertTrue(classes.stream().anyMatch(clazz ->
                clazz.className().equals("Mode")
                        && clazz.kind().equals(CodeIndexStore.CLASS_KIND_ENUM)));
        assertTrue(classes.stream().anyMatch(clazz ->
                clazz.className().equals("Marker")
                        && clazz.kind().equals(CodeIndexStore.CLASS_KIND_ANNOTATION)));
        assertTrue(classes.stream().anyMatch(clazz ->
                clazz.qualifiedName().equals("advanced.AdvancedBox.StaticNested")));

        List<CodeIndexStore.MethodRecord> methods =
                stateStore.codeIndex.findMethodsByFile(file.fileId());
        assertTrue(methods.stream().anyMatch(method ->
                method.kind().equals(CodeIndexStore.METHOD_KIND_CONSTRUCTOR)
                        && method.signature().equals("AdvancedBox(String name)")));
        assertEquals(2, methods.stream()
                .filter(method -> method.methodName().equals("overload"))
                .count());
        CodeIndexStore.MethodRecord generic = methods.stream()
                .filter(method -> method.methodName().equals("generic"))
                .findFirst().orElseThrow();
        assertEquals("<R> R generic(R value, List<? extends T> numbers)",
                generic.signature());
        assertEquals("[\"R\",\"List<? extends T>\"]", generic.parameterTypes());
        assertTrue(generic.chunkText().contains("Number::intValue"));
        assertEquals(sha256(generic.chunkText()), generic.chunkSha256());

        List<CodeIndexStore.FieldRecord> fields =
                stateStore.codeIndex.findFieldsByFile(file.fileId());
        assertTrue(fields.stream().anyMatch(field ->
                field.fieldName().equals("name")
                        && field.fieldType().equals("String")));
        assertTrue(fields.stream().anyMatch(field ->
                field.fieldName().equals("description")
                        && field.fieldType().equals("String")));
    }

    @Test
    void reindexUpdatesMethodChunkHashWhenMethodBodyChanges()
            throws Exception {
        write("src/main/java/demo/HashBox.java",
                hashFixture("return \"one\";"));
        indexer.index(tempDir);
        CodeIndexStore.CodeFileRecord firstFile = stateStore.codeIndex
                .findFile("src/main/java/demo/HashBox.java").orElseThrow();
        String firstHash = stateStore.codeIndex
                .findMethodsByFile(firstFile.fileId()).getFirst().chunkSha256();

        write("src/main/java/demo/HashBox.java",
                hashFixture("return \"two\";"));
        indexer.index(tempDir);

        CodeIndexStore.CodeFileRecord secondFile = stateStore.codeIndex
                .findFile("src/main/java/demo/HashBox.java").orElseThrow();
        CodeIndexStore.MethodRecord secondMethod = stateStore.codeIndex
                .findMethodsByFile(secondFile.fileId()).getFirst();
        assertFalse(firstHash.equals(secondMethod.chunkSha256()));
        assertTrue(secondMethod.chunkText().contains("return \"two\";"));
    }

    private Path write(String relativePath, String source) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        return path;
    }

    private String simpleClass(String className, String methodName) {
        return """
                package demo;

                public class %s {
                    public void %s() {
                    }
                }
                """.formatted(className, methodName);
    }

    private String advancedFixtureSource() {
        return """
                package advanced;

                import java.io.Serializable;
                import java.util.List;
                import java.util.Map;
                import static java.util.Comparator.comparing;

                public sealed interface Contract permits AdvancedBox {
                    String name();
                }

                public record Point(int x, int y) {
                    public int sum() {
                        return x + y;
                    }
                }

                @interface Marker { String value(); }

                enum Mode { ON, OFF }

                public final class AdvancedBox<T extends Number> extends BaseBox implements Contract, Serializable {
                    private static final int VERSION = 1;
                    private String name, description;

                    public AdvancedBox(String name) {
                        this.name = name;
                    }

                    @Override
                    public String name() {
                        return name;
                    }

                    public String overload(String value) {
                        return value;
                    }

                    public String overload(int value) {
                        return Integer.toString(value);
                    }

                    public <R> R generic(R value, List<? extends T> numbers) {
                        Runnable runnable = () -> System.out.println(value);
                        var sorted = numbers.stream().map(Number::intValue).sorted().toList();
                        return switch (sorted.size()) {
                            case 0 -> value;
                            default -> value;
                        };
                    }

                    static class StaticNested { void nested() {} }

                    class Inner { int inside; }
                }
                """;
    }

    private String hashFixture(String returnStatement) {
        return """
                package demo;

                class HashBox {
                    String value() {
                        %s
                    }
                }
                """.formatted(returnStatement);
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
