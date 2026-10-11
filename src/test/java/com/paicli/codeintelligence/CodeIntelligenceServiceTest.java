package com.paicli.codeintelligence;

import com.paicli.codeindex.CodeIndexResult;
import com.paicli.codeindex.CodeIndexer;
import com.paicli.state.CodeSearchAuditStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIntelligenceServiceTest {

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
    void queryRefreshesIndexBeforeLookup() throws Exception {
        write("src/main/java/com/example/UserService.java", """
                package com.example;
                public class UserService {
                    User find(String id) { return null; }
                }
                class User {}
                """);
        CountingIndexer indexer = new CountingIndexer(stateStore);
        CodeIntelligenceService service = service(indexer,
                stateStore.codeSearchAudits);

        SymbolLookupResult result =
                service.lookupMethod("com.example.UserService", "find");

        assertEquals(1, indexer.count);
        assertEquals(ResolutionStatus.EXACT, result.status());
        assertEquals(1, result.methods().size());
    }

    @Test
    void buildContextWritesSelectedContextHash() throws Exception {
        write("src/main/java/com/example/UserService.java", """
                package com.example;
                public class UserService {
                    String repository;
                    User find(String id) { return null; }
                }
                class User {}
                """);
        write("src/main/java/com/client/Caller.java", """
                package com.client;
                import com.example.UserService;
                public class Caller {
                    void call() { UserService.find("1"); }
                }
                """);

        CodeIntelligenceResult result = service(new CountingIndexer(stateStore),
                stateStore.codeSearchAudits)
                .buildContextForMethod("com.example.UserService", "find");

        assertFalse(result.context().contextHash().isBlank());
        CodeSearchAuditStore.CodeSearchAuditRecord serviceAudit =
                recentServiceAudit("service.context_method");
        assertEquals(CodeSearchAuditStore.STATUS_SUCCEEDED, serviceAudit.status());
        assertEquals(result.context().contextHash(),
                serviceAudit.selectedContextHash());
    }

    @Test
    void directCallSiteServiceQueryWritesAudit() throws Exception {
        write("src/main/java/com/example/A.java", """
                package com.example;
                public class A {
                    void a() { b(); }
                    void b() {}
                }
                """);

        CallSiteResult result = service(new CountingIndexer(stateStore),
                stateStore.codeSearchAudits)
                .findDirectCallSites("com.example.A", "b");

        assertEquals(1, result.callSites().size());
        CodeSearchAuditStore.CodeSearchAuditRecord serviceAudit =
                recentServiceAudit("service.direct_call_sites");
        assertEquals(CodeSearchAuditStore.STATUS_SUCCEEDED, serviceAudit.status());
        assertEquals(1, serviceAudit.resultCount());
    }

    @Test
    void refreshFailureFailsClosedAndWritesFailedAudit() throws Exception {
        CodeIntelligenceService service = service(
                new FailingIndexer(stateStore), stateStore.codeSearchAudits);

        assertThrows(SQLException.class,
                () -> service.lookupMethod("com.example.Missing", "find"));

        CodeSearchAuditStore.CodeSearchAuditRecord serviceAudit =
                recentServiceAudit("service.lookup_method");
        assertEquals(CodeSearchAuditStore.STATUS_FAILED, serviceAudit.status());
        assertTrue(serviceAudit.errorExcerpt().contains("refresh failed"));
    }

    @Test
    void auditStartFailureFailsClosed() {
        CodeIntelligenceService service = service(new CountingIndexer(stateStore),
                new StartFailingAuditStore());

        assertThrows(SQLException.class,
                () -> service.lookupMethod("com.example.Missing", "find"));
    }

    @Test
    void auditCompleteFailureIsNotSwallowed() throws Exception {
        write("src/main/java/com/example/UserService.java", """
                package com.example;
                public class UserService {
                    User find(String id) { return null; }
                }
                class User {}
                """);
        CompleteFailingAuditStore audits = new CompleteFailingAuditStore();
        CodeIntelligenceService service = service(
                new CountingIndexer(stateStore), audits);

        assertThrows(SQLException.class,
                () -> service.lookupMethod("com.example.UserService", "find"));
    }

    @Test
    void auditFailFailureIsNotSwallowed() {
        CodeIntelligenceService service = service(new FailingIndexer(stateStore),
                new FailFailingAuditStore());

        assertThrows(SQLException.class,
                () -> service.lookupMethod("com.example.Missing", "find"));
    }

    @Test
    void parseErrorFileDoesNotParticipateInSymbolContext() throws Exception {
        write("src/main/java/com/example/Good.java", """
                package com.example;
                public class Good {
                    void ok() {}
                }
                """);
        write("src/main/java/com/example/Broken.java",
                "package com.example; public class Broken { void bad( {");

        CodeIntelligenceResult result = service(new CountingIndexer(stateStore),
                stateStore.codeSearchAudits)
                .buildContextForMethod("com.example.Good", "ok");

        String rendered = result.context().sections().stream()
                .map(section -> section.content())
                .reduce("", String::concat);
        assertTrue(rendered.contains("class=com.example.Good"));
        assertFalse(rendered.contains("Broken"));
        assertEquals(1, result.refreshResult().errorCount());
    }

    @Test
    void invalidWorkspaceRootFailsFast() {
        CodeIntelligenceService service = new CodeIntelligenceService(
                tempDir.resolve("missing"),
                new CountingIndexer(stateStore),
                new SymbolLookupService(stateStore.codeIndex,
                        stateStore.codeSearchAudits),
                new DirectCallSiteSearch(stateStore.codeIndex,
                        new SymbolLookupService(stateStore.codeIndex,
                                stateStore.codeSearchAudits),
                        stateStore.codeSearchAudits),
                new CodeContextBuilder(stateStore.codeIndex),
                stateStore.codeSearchAudits);

        assertThrows(IllegalArgumentException.class,
                () -> service.lookupMethod("com.example.Missing", "find"));
    }

    private CodeIntelligenceService service(CodeIndexer indexer,
                                            CodeSearchAuditStore audits) {
        SymbolLookupService symbols = new SymbolLookupService(
                stateStore.codeIndex, audits);
        DirectCallSiteSearch callSites = new DirectCallSiteSearch(
                stateStore.codeIndex, symbols, audits);
        return new CodeIntelligenceService(tempDir, indexer, symbols, callSites,
                new CodeContextBuilder(stateStore.codeIndex), audits);
    }

    private CodeSearchAuditStore.CodeSearchAuditRecord recentServiceAudit(
            String queryType) throws Exception {
        return stateStore.codeSearchAudits.findRecentAudits(20).stream()
                .filter(record -> record.queryInput() != null
                        && record.queryInput().contains(queryType))
                .findFirst().orElseThrow();
    }

    private void write(String relativePath, String source) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static class CountingIndexer extends CodeIndexer {
        int count;

        CountingIndexer(StateStore stateStore) {
            super(stateStore.codeIndex);
        }

        @Override
        public CodeIndexResult index(Path workspaceRoot) throws SQLException {
            count++;
            return super.index(workspaceRoot);
        }
    }

    private static class FailingIndexer extends CodeIndexer {
        FailingIndexer(StateStore stateStore) {
            super(stateStore.codeIndex);
        }

        @Override
        public CodeIndexResult index(Path workspaceRoot) throws SQLException {
            throw new SQLException("refresh failed");
        }
    }

    private static class StartFailingAuditStore extends CodeSearchAuditStore {
        StartFailingAuditStore() {
            super(null);
        }

        @Override
        public void startAudit(String auditId, String queryType,
                               String queryInput) throws SQLException {
            throw new SQLException("audit start failed");
        }
    }

    private static class CompleteFailingAuditStore extends CodeSearchAuditStore {
        final List<String> serviceAuditIds = new ArrayList<>();

        CompleteFailingAuditStore() {
            super(null);
        }

        @Override
        public void startAudit(String auditId, String queryType,
                               String queryInput) {
            if (queryInput != null && queryInput.contains("service.")) {
                serviceAuditIds.add(auditId);
            }
        }

        @Override
        public void completeAudit(String auditId, int resultCount,
                                  boolean ambiguous, String confidence,
                                  String selectedContextHash,
                                  long durationMillis) throws SQLException {
            if (serviceAuditIds.contains(auditId)) {
                throw new SQLException("audit complete failed");
            }
        }

        @Override
        public void failAudit(String auditId, String errorExcerpt,
                              long durationMillis) {
        }
    }

    private static class FailFailingAuditStore extends CodeSearchAuditStore {
        FailFailingAuditStore() {
            super(null);
        }

        @Override
        public void startAudit(String auditId, String queryType,
                               String queryInput) {
        }

        @Override
        public void failAudit(String auditId, String errorExcerpt,
                              long durationMillis) throws SQLException {
            throw new SQLException("audit fail failed");
        }
    }
}
