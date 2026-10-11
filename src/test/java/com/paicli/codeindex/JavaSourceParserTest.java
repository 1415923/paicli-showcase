package com.paicli.codeindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceParserTest {

    @TempDir
    Path tempDir;

    private final JavaSourceParser parser = new JavaSourceParser();

    @Test
    void parsesPackageImportsAndClassLikeDeclarations() {
        JavaSourceParseResult result = parser.parse(
                "src/main/java/demo/Sample.java", fixtureSource());

        assertEquals(JavaSourceParseResult.ParseStatus.OK, result.status());
        assertEquals("demo", result.packageName());
        assertEquals(List.of(
                "java.util.List",
                "static java.util.Collections.emptyList"
        ), result.imports());
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Sample")
                        && clazz.qualifiedName().equals("demo.Sample")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_CLASS)
                        && clazz.modifiers().contains("public")
                        && clazz.extendsName().equals("Base")
                        && clazz.implementsNames().contains("Runnable")));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Worker")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_INTERFACE)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Mode")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_ENUM)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Marker")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_ANNOTATION)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Point")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_RECORD)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Inner")
                        && clazz.qualifiedName().equals("demo.Sample.Inner")));
    }

    @Test
    void parsesFieldsMethodsConstructorsLineRangesAndChunks() {
        String source = fixtureSource();
        JavaSourceParseResult result = parser.parse(
                "src/main/java/demo/Sample.java", source);

        ParsedField field = result.fields().stream()
                .filter(value -> value.fieldName().equals("name"))
                .findFirst().orElseThrow();
        assertEquals("String", field.fieldType());
        assertTrue(field.modifiers().contains("private"));
        assertTrue(field.modifiers().contains("final"));
        assertEquals("demo.Sample", field.qualifiedClassName());
        assertEquals(7, field.startLine());

        ParsedMethod constructor = result.methods().stream()
                .filter(method -> method.kind()
                        .equals(JavaSourceParser.METHOD_KIND_CONSTRUCTOR))
                .findFirst().orElseThrow();
        assertEquals("Sample(String name)", constructor.signature());
        assertEquals(List.of("String"), constructor.parameterTypes());
        assertEquals(9, constructor.startLine());
        assertTrue(constructor.chunkText().startsWith("    public Sample"));
        assertEquals(sha256(constructor.chunkText()), constructor.chunkSha256());

        ParsedMethod greet = result.methods().stream()
                .filter(method -> method.methodName().equals("greet"))
                .findFirst().orElseThrow();
        assertEquals(JavaSourceParser.METHOD_KIND_METHOD, greet.kind());
        assertEquals("String greet(String prefix, int count)", greet.signature());
        assertEquals("String", greet.returnType());
        assertEquals(List.of("String", "int"), greet.parameterTypes());
        assertTrue(greet.chunkText().contains("return prefix + name + count;"));
        assertEquals(sha256(greet.chunkText()), greet.chunkSha256());
    }

    @Test
    void parsePathReadsWorkspaceRelativeSource() throws Exception {
        Path source = tempDir.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, fixtureSource(), StandardCharsets.UTF_8);

        JavaSourceParseResult result =
                parser.parse(tempDir, "src/main/java/demo/Sample.java");

        assertEquals(JavaSourceParseResult.ParseStatus.OK, result.status());
        assertEquals("src/main/java/demo/Sample.java", result.relativePath());
        assertFalse(result.methods().isEmpty());
    }

    @Test
    void malformedJavaReturnsErrorResultWithBoundedExcerpt() {
        JavaSourceParseResult result = parser.parse(
                "src/main/java/demo/Broken.java",
                "package demo;\npublic class Broken {\n void nope( {\n");

        assertEquals(JavaSourceParseResult.ParseStatus.ERROR, result.status());
        assertEquals("src/main/java/demo/Broken.java", result.relativePath());
        assertNotNull(result.errorExcerpt());
        assertTrue(result.errorExcerpt().length() <= 2000);
        assertTrue(result.classes().isEmpty());
        assertTrue(result.methods().isEmpty());
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void parsesModernJavaSyntaxSmokeWithoutTypeSolving() {
        String source = """
                package modern;

                public sealed interface Shape permits Circle {
                    default String label() {
                        return switch (this) {
                            case Circle c -> c.name();
                        };
                    }
                }

                record Circle(String name) implements Shape {}
                """;

        JavaSourceParseResult result =
                parser.parse("src/main/java/modern/Shape.java", source);

        assertEquals(JavaSourceParseResult.ParseStatus.OK, result.status());
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Shape")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_INTERFACE)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Circle")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_RECORD)));
    }

    @Test
    void parsesJava17And21FixtureMetadataLineRangesAndChunks() {
        String source = advancedFixtureSource();
        JavaSourceParseResult result =
                parser.parse("src/main/java/advanced/AdvancedBox.java", source);

        assertEquals(JavaSourceParseResult.ParseStatus.OK, result.status());
        assertEquals("advanced", result.packageName());
        assertEquals(List.of(
                "java.io.Serializable",
                "java.util.List",
                "java.util.Map",
                "static java.util.Comparator.comparing"
        ), result.imports());

        ParsedClass advancedBox = result.classes().stream()
                .filter(clazz -> clazz.className().equals("AdvancedBox"))
                .findFirst().orElseThrow();
        assertEquals(JavaSourceParser.CLASS_KIND_CLASS, advancedBox.kind());
        assertEquals("advanced.AdvancedBox", advancedBox.qualifiedName());
        assertEquals("BaseBox", advancedBox.extendsName());
        assertEquals(List.of("Contract", "Serializable"),
                advancedBox.implementsNames());
        assertTrue(advancedBox.modifiers().contains("public"));
        assertTrue(advancedBox.modifiers().contains("final"));
        assertEquals(22, advancedBox.startLine());
        assertEquals(55, advancedBox.endLine());

        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Contract")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_INTERFACE)
                        && clazz.modifiers().contains("sealed")));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Point")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_RECORD)
                        && clazz.startLine().equals(12)
                        && clazz.endLine().equals(16)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Marker")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_ANNOTATION)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Mode")
                        && clazz.kind().equals(JavaSourceParser.CLASS_KIND_ENUM)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("StaticNested")
                        && clazz.qualifiedName()
                        .equals("advanced.AdvancedBox.StaticNested")
                        && clazz.startLine().equals(52)));
        assertTrue(result.classes().stream().anyMatch(clazz ->
                clazz.className().equals("Inner")
                        && clazz.qualifiedName().equals("advanced.AdvancedBox.Inner")
                        && clazz.startLine().equals(54)));

        assertTrue(result.fields().stream().anyMatch(field ->
                field.fieldName().equals("VERSION")
                        && field.modifiers().contains("static")
                        && field.modifiers().contains("final")
                        && field.startLine().equals(23)));
        assertTrue(result.fields().stream().anyMatch(field ->
                field.fieldName().equals("name")
                        && field.fieldType().equals("String")
                        && field.startLine().equals(24)));
        assertTrue(result.fields().stream().anyMatch(field ->
                field.fieldName().equals("description")
                        && field.fieldType().equals("String")
                        && field.startLine().equals(24)));

        ParsedMethod constructor = result.methods().stream()
                .filter(method -> method.kind()
                        .equals(JavaSourceParser.METHOD_KIND_CONSTRUCTOR)
                        && method.qualifiedClassName()
                        .equals("advanced.AdvancedBox"))
                .findFirst().orElseThrow();
        assertEquals("AdvancedBox(String name)", constructor.signature());
        assertEquals(26, constructor.startLine());
        assertEquals(28, constructor.endLine());
        assertTrue(constructor.chunkText().contains("this.name = name;"));
        assertEquals(sha256(constructor.chunkText()), constructor.chunkSha256());

        List<ParsedMethod> overloads = result.methods().stream()
                .filter(method -> method.methodName().equals("overload"))
                .toList();
        assertEquals(2, overloads.size());
        assertTrue(overloads.stream().anyMatch(method ->
                method.signature().equals("String overload(String value)")
                        && method.chunkText().contains("return value;")));
        assertTrue(overloads.stream().anyMatch(method ->
                method.signature().equals("String overload(int value)")
                        && method.chunkText()
                        .contains("Integer.toString(value);")));

        ParsedMethod generic = result.methods().stream()
                .filter(method -> method.methodName().equals("generic"))
                .findFirst().orElseThrow();
        assertEquals("<R> R generic(R value, List<? extends T> numbers)",
                generic.signature());
        assertEquals(List.of("R", "List<? extends T>"), generic.parameterTypes());
        assertEquals(43, generic.startLine());
        assertEquals(50, generic.endLine());
        assertTrue(generic.chunkText().contains("Runnable runnable = () ->"));
        assertTrue(generic.chunkText().contains("Number::intValue"));
        assertTrue(generic.chunkText().contains("return switch"));
        assertEquals(sha256(generic.chunkText()), generic.chunkSha256());
    }

    @Test
    void methodChunkHashChangesWhenMethodSourceChanges() {
        ParsedMethod first = parser.parse(
                        "src/main/java/demo/HashBox.java",
                        hashFixture("return \"one\";"))
                .methods().stream()
                .filter(method -> method.methodName().equals("value"))
                .findFirst().orElseThrow();
        ParsedMethod second = parser.parse(
                        "src/main/java/demo/HashBox.java",
                        hashFixture("return \"two\";"))
                .methods().stream()
                .filter(method -> method.methodName().equals("value"))
                .findFirst().orElseThrow();

        assertFalse(first.chunkSha256().equals(second.chunkSha256()));
        assertTrue(first.chunkText().contains("return \"one\";"));
        assertTrue(second.chunkText().contains("return \"two\";"));
    }

    private String fixtureSource() {
        return """
                package demo;

                import java.util.List;
                import static java.util.Collections.emptyList;

                public class Sample extends Base implements Runnable, AutoCloseable {
                    private final String name;

                    public Sample(String name) {
                        this.name = name;
                    }

                    public String greet(String prefix, int count) {
                        return prefix + name + count;
                    }

                    @Override
                    public void run() {
                    }

                    @Override
                    public void close() {
                    }

                    static class Inner {
                        int value;
                    }
                }

                interface Worker {
                    void work();
                }

                enum Mode {
                    ON, OFF
                }

                @interface Marker {
                }

                record Point(int x, int y) {
                }
                """;
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
