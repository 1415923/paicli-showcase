package com.paicli.codeintelligence;

import com.paicli.state.CodeIndexStore;
import com.paicli.state.CodeSearchAuditStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectCallSiteSearchTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;
    private DirectCallSiteSearch search;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
        SymbolLookupService symbols = new SymbolLookupService(
                stateStore.codeIndex, stateStore.codeSearchAudits);
        search = new DirectCallSiteSearch(
                stateStore.codeIndex, symbols, stateStore.codeSearchAudits);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void sameClassUnqualifiedCallReturnsHighBoundedHeuristic() throws Exception {
        insertClass("file-a", "class-a", "com.example", "A",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(
                        method("method-a", "file-a", "class-a", "a",
                                "void a()", "[]", 10,
                                "void a() {\n    b();\n}"),
                        method("method-b", "file-a", "class-a", "b",
                                "void b()", "[]", 20,
                                "void b() {\n}")
                ));

        CallSiteResult result = search.search("com.example.A", "b");

        assertEquals(ResolutionStatus.BOUNDED_HEURISTIC, result.status());
        assertEquals(ConfidenceLevel.HIGH, result.confidence());
        assertEquals(1, result.callSites().size());
        CallSiteCandidate candidate = result.callSites().getFirst();
        assertEquals("com.example.A", candidate.callerClass());
        assertEquals("a", candidate.callerMethod());
        assertEquals("b()", candidate.callExpressionText());
        assertEquals(11, candidate.callLine());
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.SAME_CLASS_UNQUALIFIED_CALL));
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.ARGUMENT_ARITY_MATCH));
    }

    @Test
    void staticClassScopeCallHasStaticEvidence() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-find", "file-service", "class-service",
                        "find", "User find()", "[]", 5,
                        "static User find() {\n    return null;\n}")));
        insertClass("file-caller", "class-caller", "com.example", "Caller",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-call", "file-caller", "class-caller",
                        "call", "void call()", "[]", 12,
                        "void call() {\n    UserService.find();\n}")));

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(1, result.callSites().size());
        CallSiteCandidate candidate = result.callSites().getFirst();
        assertEquals(ConfidenceLevel.HIGH, candidate.confidence());
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.STATIC_CLASS_SCOPE_MATCH));
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.PACKAGE_MATCH));
    }

    @Test
    void importedClassScopeCallHasImportEvidence() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-find", "file-service", "class-service",
                        "find", "User find()", "[]", 5,
                        "static User find() {\n    return null;\n}")));
        insertClass("file-caller", "class-caller", "com.client", "Caller",
                CodeIndexStore.STATUS_INDEXED,
                "[\"com.example.UserService\"]",
                List.of(method("method-call", "file-caller", "class-caller",
                        "call", "void call()", "[]", 12,
                        "void call() {\n    UserService.find();\n}")));

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(1, result.callSites().size());
        assertTrue(result.callSites().getFirst().evidence()
                .contains(CallSiteEvidence.IMPORT_MATCH));
    }

    @Test
    void instanceReceiverKeepsUnknownReceiverLowConfidence() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-find", "file-service", "class-service",
                        "find", "User find()", "[]", 5,
                        "User find() {\n    return null;\n}")));
        insertClass("file-caller", "class-caller", "com.client", "Caller",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-call", "file-caller", "class-caller",
                        "call", "void call()", "[]", 12,
                        "void call() {\n    service.find();\n}")));

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(1, result.callSites().size());
        CallSiteCandidate candidate = result.callSites().getFirst();
        assertNotEquals(ConfidenceLevel.HIGH, candidate.confidence());
        assertNotEquals(ResolutionStatus.EXACT, candidate.resolutionStatus());
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.UNKNOWN_RECEIVER));
    }

    @Test
    void overloadByArityNarrowsCandidates() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(
                        method("method-find-one", "file-service", "class-service",
                                "find", "User find(String name)", "[\"String\"]",
                                5, "User find(String name) {\n    return null;\n}"),
                        method("method-find-two", "file-service", "class-service",
                                "find", "User find(String name, int page)",
                                "[\"String\",\"int\"]", 9,
                                "User find(String name, int page) {\n    return null;\n}"),
                        method("method-call", "file-service", "class-service",
                                "call", "void call()", "[]", 14,
                                "void call() {\n    find(\"x\");\n}")
                ));

        CallSiteResult result = search.search("com.example.UserService", "find");

        CallSiteCandidate candidate = result.callSites().getFirst();
        assertEquals(1, candidate.argumentCount());
        assertEquals(1, candidate.targetCandidates().size());
        assertEquals("User find(String name)",
                candidate.targetCandidates().getFirst().signature());
    }

    @Test
    void sameArityOverloadRemainsAmbiguous() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(
                        method("method-find-string", "file-service", "class-service",
                                "find", "User find(String value)", "[\"String\"]",
                                5, "User find(String value) {\n    return null;\n}"),
                        method("method-find-object", "file-service", "class-service",
                                "find", "User find(Object value)", "[\"Object\"]",
                                9, "User find(Object value) {\n    return null;\n}"),
                        method("method-call", "file-service", "class-service",
                                "call", "void call()", "[]", 14,
                                "void call() {\n    find(value);\n}")
                ));

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(ResolutionStatus.AMBIGUOUS, result.status());
        CallSiteCandidate candidate = result.callSites().getFirst();
        assertEquals(2, candidate.targetCandidates().size());
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.OVERLOAD_AMBIGUOUS));
    }

    @Test
    void ambiguousSimpleClassScopeDoesNotSelectOneTarget() throws Exception {
        insertClass("file-a", "class-a", "com.a", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-a-find", "file-a", "class-a",
                        "find", "User find()", "[]", 5,
                        "static User find() {\n    return null;\n}")));
        insertClass("file-b", "class-b", "com.b", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-b-find", "file-b", "class-b",
                        "find", "User find()", "[]", 5,
                        "static User find() {\n    return null;\n}")));
        insertClass("file-caller", "class-caller", "com.client", "Caller",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-call", "file-caller", "class-caller",
                        "call", "void call()", "[]", 20,
                        "void call() {\n    UserService.find();\n}")));

        CallSiteResult result = search.search("UserService", "find");

        assertEquals(ResolutionStatus.AMBIGUOUS, result.status());
        CallSiteCandidate candidate = result.callSites().getFirst();
        assertEquals(2, candidate.targetCandidates().size());
        assertTrue(candidate.evidence()
                .contains(CallSiteEvidence.CLASS_AMBIGUOUS));
    }

    @Test
    void errorDeletedAndSkippedCallerFilesAreExcluded() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-find", "file-service", "class-service",
                        "find", "User find()", "[]", 5,
                        "User find() {\n    return null;\n}")));
        insertCallerWithStatus("file-error", "class-error", "com.error",
                CodeIndexStore.STATUS_ERROR);
        insertCallerWithStatus("file-skipped", "class-skipped", "com.skipped",
                CodeIndexStore.STATUS_SKIPPED);
        insertCallerWithStatus("file-deleted", "class-deleted", "com.deleted",
                CodeIndexStore.STATUS_INDEXED);
        stateStore.codeIndex.markDeleted("file-deleted");

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(ResolutionStatus.NOT_FOUND, result.status());
        assertTrue(result.callSites().isEmpty());
    }

    @Test
    void commentsAndStringsAreNotCallSites() throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-find", "file-service", "class-service",
                        "find", "User find()", "[]", 5,
                        "User find() {\n    return null;\n}")));
        insertClass("file-caller", "class-caller", "com.client", "Caller",
                CodeIndexStore.STATUS_INDEXED,
                "[\"com.example.UserService\"]",
                List.of(method("method-call", "file-caller", "class-caller",
                        "call", "void call()", "[]", 10,
                        "void call() {\n"
                                + "    String text = \"UserService.find()\";\n"
                                + "    // UserService.find();\n"
                                + "}")));

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(ResolutionStatus.NOT_FOUND, result.status());
        assertTrue(result.callSites().isEmpty());
    }

    @Test
    void searchWritesCallSiteAudit() throws Exception {
        insertClass("file-a", "class-a", "com.example", "A",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(
                        method("method-a", "file-a", "class-a", "a",
                                "void a()", "[]", 10,
                                "void a() {\n    b();\n}"),
                        method("method-b", "file-a", "class-a", "b",
                                "void b()", "[]", 20,
                                "void b() {\n}")
                ));

        CallSiteResult result = search.search("com.example.A", "b");

        CodeSearchAuditStore.CodeSearchAuditRecord audit =
                stateStore.codeSearchAudits.findRecentAudits(3).stream()
                        .filter(record -> CodeSearchAuditStore.TYPE_CALL_SITE
                                .equals(record.queryType()))
                        .findFirst().orElseThrow();
        assertEquals(CodeSearchAuditStore.STATUS_SUCCEEDED, audit.status());
        assertEquals(result.resultCount(), audit.resultCount());
        assertEquals(false, audit.ambiguous());
        assertEquals(CodeSearchAuditStore.CONFIDENCE_HIGH, audit.confidence());
    }

    @Test
    void parseFailureInOneCallerChunkDoesNotBlockOtherResults()
            throws Exception {
        insertClass("file-service", "class-service", "com.example", "UserService",
                CodeIndexStore.STATUS_INDEXED, "[]",
                List.of(method("method-find", "file-service", "class-service",
                        "find", "User find()", "[]", 5,
                        "User find() {\n    return null;\n}")));
        insertClass("file-good", "class-good", "com.client", "GoodCaller",
                CodeIndexStore.STATUS_INDEXED,
                "[\"com.example.UserService\"]",
                List.of(method("method-good", "file-good", "class-good",
                        "call", "void call()", "[]", 10,
                        "void call() {\n    UserService.find();\n}")));
        insertClass("file-bad", "class-bad", "com.client", "BadCaller",
                CodeIndexStore.STATUS_INDEXED,
                "[\"com.example.UserService\"]",
                List.of(method("method-bad", "file-bad", "class-bad",
                        "broken", "void broken()", "[]", 10,
                        "void broken( {\n    UserService.find();\n}")));

        CallSiteResult result = search.search("com.example.UserService", "find");

        assertEquals(1, result.callSites().size());
        assertFalse(result.issues().isEmpty());
        assertTrue(result.issues().getFirst().contains("PARSE_ERROR"));
    }

    private void insertCallerWithStatus(String fileId, String classId,
                                        String packageName, String status)
            throws Exception {
        insertClass(fileId, classId, packageName, "Caller", status,
                "[\"com.example.UserService\"]",
                List.of(method("method-" + fileId, fileId, classId,
                        "call", "void call()", "[]", 10,
                        "void call() {\n    UserService.find();\n}")));
    }

    private void insertClass(String fileId, String classId, String packageName,
                             String className, String status, String importsJson,
                             List<CodeIndexStore.MethodRecord> methods)
            throws Exception {
        String relativePath = "src/main/java/"
                + packageName.replace('.', '/') + "/" + className + ".java";
        stateStore.codeIndex.replaceFileIndex(
                new CodeIndexStore.CodeFileRecord(
                        fileId, relativePath, "java", packageName, importsJson,
                        "sha-" + fileId, 100L, 200L, status, null, null, null),
                List.of(new CodeIndexStore.ClassRecord(
                        classId, fileId, packageName, className,
                        packageName + "." + className,
                        CodeIndexStore.CLASS_KIND_CLASS, 1, 50,
                        "public", null, "[]")),
                methods, List.of());
    }

    private CodeIndexStore.MethodRecord method(
            String methodId, String fileId, String classId, String methodName,
            String signature, String parameterTypes, int startLine,
            String chunkText) throws Exception {
        return new CodeIndexStore.MethodRecord(
                methodId, fileId, classId, methodName,
                CodeIndexStore.METHOD_KIND_METHOD, signature,
                signature.startsWith("void ") ? "void" : "User",
                parameterTypes, startLine,
                startLine + chunkText.split("\\R", -1).length - 1,
                chunkText, sha256(chunkText), "public");
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
