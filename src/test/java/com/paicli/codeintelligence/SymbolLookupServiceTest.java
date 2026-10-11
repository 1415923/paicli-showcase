package com.paicli.codeintelligence;

import com.paicli.state.CodeIndexStore;
import com.paicli.state.CodeSearchAuditStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolLookupServiceTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;
    private SymbolLookupService lookup;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
        lookup = new SymbolLookupService(
                stateStore.codeIndex, stateStore.codeSearchAudits);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void qualifiedClassExactLookupReturnsMetadata() throws Exception {
        insertService("file-foo", "class-foo", "com.example", "Foo",
                CodeIndexStore.STATUS_INDEXED,
                List.of(method("method-find", "file-foo", "class-foo",
                        "find", "String find(Long id)", "String", "[\"Long\"]")),
                List.of(field("field-name", "file-foo", "class-foo", "name")));

        SymbolLookupResult result =
                lookup.lookupClassByQualifiedName("com.example.Foo");

        assertEquals(ResolutionStatus.EXACT, result.status());
        assertEquals(ConfidenceLevel.HIGH, result.confidence());
        assertEquals(1, result.classes().size());
        ClassSymbol symbol = result.classes().getFirst();
        assertEquals("src/main/java/com/example/Foo.java", symbol.relativePath());
        assertEquals("com.example", symbol.packageName());
        assertEquals("com.example.Foo", symbol.qualifiedName());
        assertEquals(CodeIndexStore.CLASS_KIND_CLASS, symbol.kind());
        assertEquals(3, symbol.startLine());
        assertEquals(40, symbol.endLine());
    }

    @Test
    void simpleClassLookupReturnsMultipleCandidates() throws Exception {
        insertService("file-a-user", "class-a-user", "com.a", "UserService",
                CodeIndexStore.STATUS_INDEXED, List.of(), List.of());
        insertService("file-b-user", "class-b-user", "com.b", "UserService",
                CodeIndexStore.STATUS_INDEXED, List.of(), List.of());

        SymbolLookupResult result =
                lookup.lookupClassBySimpleName("UserService");

        assertEquals(ResolutionStatus.MULTIPLE_CANDIDATES, result.status());
        assertEquals(ConfidenceLevel.MEDIUM, result.confidence());
        assertEquals(2, result.classes().size());
        assertEquals("com.a.UserService", result.classes().getFirst().qualifiedName());
        assertEquals("com.b.UserService", result.classes().getLast().qualifiedName());
    }

    @Test
    void methodNameAcrossClassesReturnsCandidates() throws Exception {
        insertService("file-a", "class-a", "com.a", "Repo",
                CodeIndexStore.STATUS_INDEXED,
                List.of(method("method-a-find", "file-a", "class-a",
                        "findById", "User findById(Long id)", "User", "[\"Long\"]")),
                List.of());
        insertService("file-b", "class-b", "com.b", "Repo",
                CodeIndexStore.STATUS_INDEXED,
                List.of(method("method-b-find", "file-b", "class-b",
                        "findById", "Order findById(String id)", "Order", "[\"String\"]")),
                List.of());

        SymbolLookupResult result = lookup.lookupMethodsByName("findById");

        assertEquals(ResolutionStatus.MULTIPLE_CANDIDATES, result.status());
        assertEquals(2, result.methods().size());
        assertEquals("com.a.Repo", result.methods().getFirst().qualifiedName());
        assertEquals("com.b.Repo", result.methods().getLast().qualifiedName());
    }

    @Test
    void classAndMethodOverloadReturnsAllCandidates() throws Exception {
        insertService("file-user", "class-user", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED,
                List.of(
                        method("method-find-string", "file-user", "class-user",
                                "find", "User find(String name)", "User", "[\"String\"]"),
                        method("method-find-long", "file-user", "class-user",
                                "find", "User find(Long id)", "User", "[\"Long\"]")
                ),
                List.of());

        SymbolLookupResult result =
                lookup.lookupMethodsByClassAndName("com.example.UserService", "find");

        assertEquals(ResolutionStatus.MULTIPLE_CANDIDATES, result.status());
        assertEquals(ConfidenceLevel.MEDIUM, result.confidence());
        assertEquals(2, result.methods().size());
        assertEquals(List.of("User find(Long id)", "User find(String name)"),
                result.methods().stream().map(MethodSymbol::signature).toList());
        MethodSymbol longOverload = result.methods().stream()
                .filter(method -> method.signature().equals("User find(Long id)"))
                .findFirst().orElseThrow();
        assertEquals("chunk-method-find-long", longOverload.chunkText());
        assertEquals("sha-method-find-long", longOverload.chunkSha256());
    }

    @Test
    void classAndMethodExactBySignatureIsBoundedBehavior() throws Exception {
        insertService("file-user", "class-user", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED,
                List.of(
                        method("method-find-string", "file-user", "class-user",
                                "find", "User find(String name)", "User", "[\"String\"]"),
                        method("method-find-long", "file-user", "class-user",
                                "find", "User find(Long id)", "User", "[\"Long\"]")
                ),
                List.of());

        SymbolLookupResult result =
                lookup.lookupMethodsByClassAndName("com.example.UserService", "find");

        assertEquals(ResolutionStatus.MULTIPLE_CANDIDATES, result.status());
        assertTrue(result.message().contains("multiple overloads"));
    }

    @Test
    void fieldLookupByClassAndName() throws Exception {
        insertService("file-user", "class-user", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, List.of(),
                List.of(
                        field("field-name", "file-user", "class-user", "name"),
                        field("field-count", "file-user", "class-user", "count")
                ));

        SymbolLookupResult all =
                lookup.lookupFieldsByClass("com.example.UserService");
        SymbolLookupResult named =
                lookup.lookupFieldsByClassAndName("com.example.UserService", "name");

        assertEquals(ResolutionStatus.MULTIPLE_CANDIDATES, all.status());
        assertEquals(2, all.fields().size());
        assertEquals(ResolutionStatus.EXACT, named.status());
        assertEquals(1, named.fields().size());
        assertEquals("name", named.fields().getFirst().fieldName());
        assertEquals("src/main/java/com/example/UserService.java",
                named.fields().getFirst().relativePath());
    }

    @Test
    void errorDeletedAndSkippedSymbolsAreFiltered() throws Exception {
        insertService("file-good", "class-good", "com.example", "Target",
                CodeIndexStore.STATUS_INDEXED,
                List.of(method("method-good", "file-good", "class-good",
                        "run", "void run()", "void", "[]")),
                List.of(field("field-good", "file-good", "class-good", "value")));
        insertService("file-error", "class-error", "com.error", "Target",
                CodeIndexStore.STATUS_ERROR,
                List.of(method("method-error", "file-error", "class-error",
                        "run", "void run()", "void", "[]")),
                List.of(field("field-error", "file-error", "class-error", "value")));
        insertService("file-skipped", "class-skipped", "com.skipped", "Target",
                CodeIndexStore.STATUS_SKIPPED,
                List.of(method("method-skipped", "file-skipped", "class-skipped",
                        "run", "void run()", "void", "[]")),
                List.of(field("field-skipped", "file-skipped", "class-skipped", "value")));
        insertService("file-deleted", "class-deleted", "com.deleted", "Target",
                CodeIndexStore.STATUS_INDEXED,
                List.of(method("method-deleted", "file-deleted", "class-deleted",
                        "run", "void run()", "void", "[]")),
                List.of(field("field-deleted", "file-deleted", "class-deleted", "value")));
        stateStore.codeIndex.markDeleted("file-deleted");

        assertEquals(1, lookup.lookupClassBySimpleName("Target").classes().size());
        assertEquals(1, lookup.lookupMethodsByName("run").methods().size());
        assertEquals(1, lookup.lookupFieldsByClassAndName("Target", "value")
                .fields().size());
    }

    @Test
    void notFoundReturnsNoneConfidenceAndEmptyCandidates() throws Exception {
        SymbolLookupResult result =
                lookup.lookupClassByQualifiedName("com.example.Missing");

        assertEquals(ResolutionStatus.NOT_FOUND, result.status());
        assertEquals(ConfidenceLevel.NONE, result.confidence());
        assertEquals(0, result.resultCount());
        assertTrue(result.classes().isEmpty());
    }

    @Test
    void lookupWritesSucceededAudit() throws Exception {
        insertService("file-foo", "class-foo", "com.example", "Foo",
                CodeIndexStore.STATUS_INDEXED, List.of(), List.of());

        SymbolLookupResult result = lookup.lookupClassBySimpleName("Foo");

        CodeSearchAuditStore.CodeSearchAuditRecord audit =
                stateStore.codeSearchAudits.findRecentAudits(1).getFirst();
        assertEquals(CodeSearchAuditStore.TYPE_SYMBOL, audit.queryType());
        assertEquals(CodeSearchAuditStore.STATUS_SUCCEEDED, audit.status());
        assertEquals(result.resultCount(), audit.resultCount());
        assertEquals(false, audit.ambiguous());
        assertEquals(CodeSearchAuditStore.CONFIDENCE_HIGH, audit.confidence());
        assertTrue(audit.queryInput().contains("class.simple"));
    }

    @Test
    void auditFailureFailsLookupClosed() {
        CodeSearchAuditStore failingAudit = new CodeSearchAuditStore(null) {
            @Override
            public void startAudit(String auditId, String queryType,
                                   String queryInput) throws SQLException {
                throw new SQLException("audit unavailable");
            }
        };
        SymbolLookupService failingLookup =
                new SymbolLookupService(stateStore.codeIndex, failingAudit);

        assertThrows(SQLException.class,
                () -> failingLookup.lookupClassBySimpleName("Any"));
    }

    private void insertService(String fileId, String classId, String packageName,
                               String className, String status,
                               List<CodeIndexStore.MethodRecord> methods,
                               List<CodeIndexStore.FieldRecord> fields)
            throws Exception {
        String relativePath = "src/main/java/"
                + packageName.replace('.', '/') + "/" + className + ".java";
        stateStore.codeIndex.replaceFileIndex(
                new CodeIndexStore.CodeFileRecord(
                        fileId, relativePath, "java", packageName,
                        "[\"java.util.List\"]", "sha-" + fileId, 100L, 200L,
                        status, null, null, null),
                List.of(new CodeIndexStore.ClassRecord(
                        classId, fileId, packageName, className,
                        packageName + "." + className,
                        CodeIndexStore.CLASS_KIND_CLASS, 3, 40,
                        "public", null, "[]")),
                methods, fields);
    }

    private CodeIndexStore.MethodRecord method(
            String methodId, String fileId, String classId, String methodName,
            String signature, String returnType, String parameterTypes) {
        return new CodeIndexStore.MethodRecord(
                methodId, fileId, classId, methodName,
                CodeIndexStore.METHOD_KIND_METHOD, signature, returnType,
                parameterTypes, 10, 12, "chunk-" + methodId, "sha-" + methodId,
                "public");
    }

    private CodeIndexStore.FieldRecord field(
            String fieldId, String fileId, String classId, String fieldName) {
        return new CodeIndexStore.FieldRecord(
                fieldId, fileId, classId, fieldName, "String",
                7, 7, "private");
    }
}
