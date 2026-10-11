package com.paicli.codeintelligence;

import com.paicli.state.CodeIndexStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextBuilderTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;
    private SymbolLookupService symbols;
    private DirectCallSiteSearch callSites;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
        symbols = new SymbolLookupService(
                stateStore.codeIndex, stateStore.codeSearchAudits);
        callSites = new DirectCallSiteSearch(
                stateStore.codeIndex, symbols, stateStore.codeSearchAudits);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void methodContextContainsMetadataAndTargetChunkOnly() throws Exception {
        insertServiceFixture();
        MethodSymbol target = symbols.lookupMethodsByClassAndName(
                "com.example.UserService", "find").methods().getFirst();
        CallSiteResult calls = callSites.search("com.example.UserService", "find");

        CodeContext context = new CodeContextBuilder(stateStore.codeIndex)
                .buildForMethod(target, calls);
        String rendered = render(context);

        assertTrue(rendered.contains("## target"));
        assertTrue(rendered.contains("file=src/main/java/com/example/UserService.java"));
        assertTrue(rendered.contains("signature=User find(String id)"));
        assertTrue(rendered.contains("## method_chunk"));
        assertTrue(rendered.contains("return null;"));
        assertTrue(rendered.contains("## callers"));
        assertTrue(rendered.contains("UserService.find(\"1\")"));
        assertFalse(rendered.contains("void unrelated()"));
        assertFalse(context.contextHash().isBlank());
    }

    @Test
    void classContextContainsPackageImportsClassAndFieldMetadata()
            throws Exception {
        insertServiceFixture();
        ClassSymbol target = symbols.lookupClassByQualifiedName(
                "com.example.UserService").classes().getFirst();

        CodeContext context = new CodeContextBuilder(stateStore.codeIndex)
                .buildForClass(target);
        String rendered = render(context);

        assertTrue(rendered.contains("package=com.example"));
        assertTrue(rendered.contains("imports=[\"java.util.List\"]"));
        assertTrue(rendered.contains("class=com.example.UserService"));
        assertTrue(rendered.contains("repository:String"));
    }

    @Test
    void callerSummaryHonorsMaxCallers() throws Exception {
        insertServiceWithCallers(3);
        MethodSymbol target = symbols.lookupMethodsByClassAndName(
                "com.example.UserService", "find").methods().getFirst();
        CallSiteResult calls = callSites.search("com.example.UserService", "find");

        CodeContext context = new CodeContextBuilder(stateStore.codeIndex,
                new CodeContextBudget(12_000, 4_000, 1, 20, 10))
                .buildForMethod(target, calls);
        String rendered = render(context);

        assertTrue(rendered.contains("Caller.java"));
        assertFalse(rendered.contains("Caller0"));
        assertFalse(rendered.contains("Caller1"));
    }

    @Test
    void totalBudgetTruncatesDeterministicallyAndHashIsStable()
            throws Exception {
        insertServiceFixture();
        MethodSymbol target = symbols.lookupMethodsByClassAndName(
                "com.example.UserService", "find").methods().getFirst();

        CodeContextBuilder builder = new CodeContextBuilder(stateStore.codeIndex,
                new CodeContextBudget(80, 50, 10, 20, 10));
        CodeContext first = builder.buildForMethod(target, null);
        CodeContext second = builder.buildForMethod(target, null);

        assertTrue(first.truncated());
        assertTrue(first.totalChars() <= 80);
        assertEquals(first.contextHash(), second.contextHash());
    }

    @Test
    void overloadedCandidateContextKeepsCandidatesAndAmbiguityEvidence()
            throws Exception {
        insertOverloadedFixture();
        SymbolLookupResult lookup = symbols.lookupMethodsByClassAndName(
                "com.example.UserService", "find");
        CallSiteResult calls = callSites.search("com.example.UserService", "find");

        CodeContext context = new CodeContextBuilder(stateStore.codeIndex)
                .buildForLookup(lookup, calls);
        String rendered = render(context);

        assertEquals(ResolutionStatus.MULTIPLE_CANDIDATES, lookup.status());
        assertTrue(rendered.contains("User find(String id)"));
        assertTrue(rendered.contains("User find(Object id)"));
        assertTrue(rendered.contains("OVERLOAD_AMBIGUOUS"));
        assertNotEquals("", context.contextHash());
    }

    private String render(CodeContext context) {
        return context.sections().stream()
                .map(section -> "## " + section.title() + "\n"
                        + section.content() + "\n")
                .reduce("", String::concat);
    }

    private void insertServiceFixture() throws Exception {
        stateStore.codeIndex.replaceFileIndex(
                file("file-service", "src/main/java/com/example/UserService.java",
                        "com.example"),
                List.of(clazz("class-service", "file-service", "com.example",
                        "UserService")),
                List.of(
                        method("method-find", "file-service", "class-service",
                                "find", "User find(String id)", "[\"String\"]",
                                6, "User find(String id) {\n    return null;\n}"),
                        method("method-unrelated", "file-service", "class-service",
                                "unrelated", "void unrelated()", "[]",
                                10, "void unrelated() {\n}")
                ),
                List.of(new CodeIndexStore.FieldRecord(
                        "field-repository", "file-service", "class-service",
                        "repository", "String", 4, 4, "private")));
        stateStore.codeIndex.replaceFileIndex(
                file("file-caller", "src/main/java/com/client/Caller.java",
                        "com.client"),
                List.of(clazz("class-caller", "file-caller", "com.client",
                        "Caller")),
                List.of(method("method-call", "file-caller", "class-caller",
                        "call", "void call()", "[]", 20,
                        "void call() {\n    UserService.find(\"1\");\n}")),
                List.of());
    }

    private void insertServiceWithCallers(int count) throws Exception {
        insertServiceFixture();
        for (int i = 0; i < count; i++) {
            stateStore.codeIndex.replaceFileIndex(
                    file("file-caller-" + i,
                            "src/main/java/com/client/Caller" + i + ".java",
                            "com.client"),
                    List.of(clazz("class-caller-" + i, "file-caller-" + i,
                            "com.client", "Caller" + i)),
                    List.of(method("method-call-" + i, "file-caller-" + i,
                            "class-caller-" + i, "call", "void call()", "[]",
                            20 + i,
                            "void call() {\n    UserService.find(\"" + i + "\");\n}")),
                    List.of());
        }
    }

    private void insertOverloadedFixture() throws Exception {
        stateStore.codeIndex.replaceFileIndex(
                file("file-service", "src/main/java/com/example/UserService.java",
                        "com.example"),
                List.of(clazz("class-service", "file-service", "com.example",
                        "UserService")),
                List.of(
                        method("method-find-string", "file-service",
                                "class-service", "find",
                                "User find(String id)", "[\"String\"]",
                                6, "User find(String id) {\n    return null;\n}"),
                        method("method-find-object", "file-service",
                                "class-service", "find",
                                "User find(Object id)", "[\"Object\"]",
                                10, "User find(Object id) {\n    return null;\n}"),
                        method("method-call", "file-service", "class-service",
                                "call", "void call()", "[]", 15,
                                "void call() {\n    find(value);\n}")
                ),
                List.of());
    }

    private CodeIndexStore.CodeFileRecord file(String fileId, String path,
                                               String packageName) {
        return new CodeIndexStore.CodeFileRecord(
                fileId, path, "java", packageName, "[\"java.util.List\"]",
                "sha-" + fileId, 100L, 200L,
                CodeIndexStore.STATUS_INDEXED, null, null, null);
    }

    private CodeIndexStore.ClassRecord clazz(String classId, String fileId,
                                             String packageName, String name) {
        return new CodeIndexStore.ClassRecord(
                classId, fileId, packageName, name, packageName + "." + name,
                CodeIndexStore.CLASS_KIND_CLASS, 1, 40, "public", null, "[]");
    }

    private CodeIndexStore.MethodRecord method(
            String methodId, String fileId, String classId, String methodName,
            String signature, String parameterTypes, int startLine,
            String chunkText) {
        return new CodeIndexStore.MethodRecord(
                methodId, fileId, classId, methodName,
                CodeIndexStore.METHOD_KIND_METHOD, signature,
                signature.startsWith("void ") ? "void" : "User",
                parameterTypes, startLine,
                startLine + chunkText.split("\\R", -1).length - 1,
                chunkText, "sha-" + methodId, "public");
    }
}
