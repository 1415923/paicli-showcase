package com.paicli.codeintelligence;

import com.paicli.codeindex.CodeIndexResult;
import com.paicli.codeindex.CodeIndexer;
import com.paicli.state.CodeSearchAuditStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Internal read-only code-intelligence facade. */
public class CodeIntelligenceService {

    private final Path workspaceRoot;
    private final CodeIndexer indexer;
    private final SymbolLookupService symbols;
    private final DirectCallSiteSearch callSites;
    private final CodeContextBuilder contextBuilder;
    private final CodeSearchAuditStore audits;

    public CodeIntelligenceService(Path workspaceRoot,
                                   CodeIndexer indexer,
                                   SymbolLookupService symbols,
                                   DirectCallSiteSearch callSites,
                                   CodeContextBuilder contextBuilder,
                                   CodeSearchAuditStore audits) {
        this.workspaceRoot = workspaceRoot;
        this.indexer = indexer;
        this.symbols = symbols;
        this.callSites = callSites;
        this.contextBuilder = contextBuilder;
        this.audits = audits;
    }

    public SymbolLookupResult lookupMethod(String classNameOrQualifiedName,
                                           String methodName)
            throws Exception {
        return withAudit("service.lookup_method",
                classNameOrQualifiedName + "#" + methodName, () -> {
                    CodeIndexResult refresh = refresh();
                    SymbolLookupResult lookup = symbols.lookupMethodsByClassAndName(
                            classNameOrQualifiedName, methodName);
                    return new ServiceQuery(lookup, null, null, refresh);
                }).lookup();
    }

    public SymbolLookupResult lookupClass(String classNameOrQualifiedName)
            throws Exception {
        return withAudit("service.lookup_class", classNameOrQualifiedName, () -> {
            CodeIndexResult refresh = refresh();
            SymbolLookupResult lookup = classNameOrQualifiedName.contains(".")
                    ? symbols.lookupClassByQualifiedName(classNameOrQualifiedName)
                    : symbols.lookupClassBySimpleName(classNameOrQualifiedName);
            return new ServiceQuery(lookup, null, null, refresh);
        }).lookup();
    }

    public CallSiteResult findDirectCallSites(String classNameOrQualifiedName,
                                              String methodName)
            throws Exception {
        return withAudit("service.direct_call_sites",
                classNameOrQualifiedName + "#" + methodName, () -> {
                    CodeIndexResult refresh = refresh();
                    CallSiteResult result = callSites.search(
                            classNameOrQualifiedName, methodName);
                    return new ServiceQuery(null, result, null, refresh);
                }).callSites();
    }

    public CodeIntelligenceResult buildContextForMethod(
            String classNameOrQualifiedName, String methodName) throws Exception {
        ServiceQuery query = withAudit("service.context_method",
                classNameOrQualifiedName + "#" + methodName, () -> {
                    CodeIndexResult refresh = refresh();
                    SymbolLookupResult lookup = symbols.lookupMethodsByClassAndName(
                            classNameOrQualifiedName, methodName);
                    if (lookup.methods().isEmpty()) {
                        return new ServiceQuery(lookup, null,
                                contextBuilder.buildForLookup(lookup, null),
                                refresh);
                    }
                    CallSiteResult calls = callSites.search(
                            classNameOrQualifiedName, methodName);
                    CodeContext context = contextBuilder.buildForLookup(
                            lookup, calls);
                    return new ServiceQuery(lookup, calls, context, refresh);
                });
        return new CodeIntelligenceResult(query.lookup(), query.callSites(),
                query.context(), query.refreshResult());
    }

    public CodeIntelligenceResult buildContextForClass(
            String classNameOrQualifiedName) throws Exception {
        ServiceQuery query = withAudit("service.context_class",
                classNameOrQualifiedName, () -> {
                    CodeIndexResult refresh = refresh();
                    SymbolLookupResult lookup = classNameOrQualifiedName.contains(".")
                            ? symbols.lookupClassByQualifiedName(classNameOrQualifiedName)
                            : symbols.lookupClassBySimpleName(classNameOrQualifiedName);
                    CodeContext context = lookup.classes().isEmpty()
                            ? contextBuilder.buildForLookup(lookup, null)
                            : contextBuilder.buildForClass(lookup.classes().getFirst());
                    return new ServiceQuery(lookup, null, context, refresh);
                });
        return new CodeIntelligenceResult(query.lookup(), query.callSites(),
                query.context(), query.refreshResult());
    }

    private CodeIndexResult refresh() throws SQLException {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalArgumentException("workspace root is invalid");
        }
        return indexer.index(workspaceRoot);
    }

    private ServiceQuery withAudit(String queryType, String queryInput,
                                   ServiceOperation operation)
            throws Exception {
        String auditId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        audits.startAudit(auditId, auditType(queryType),
                "{\"service\":\"" + escape(queryType)
                        + "\",\"input\":\"" + escape(queryInput) + "\"}");
        try {
            ServiceQuery result = operation.run();
            audits.completeAudit(auditId, result.resultCount(),
                    result.ambiguous(), toAuditConfidence(result.confidence()),
                    result.context() == null ? null : result.context().contextHash(),
                    Duration.between(started, Instant.now()).toMillis());
            return result;
        } catch (Exception e) {
            audits.failAudit(auditId, e.getMessage(),
                    Duration.between(started, Instant.now()).toMillis());
            throw e;
        }
    }

    private String toAuditConfidence(ConfidenceLevel confidence) {
        return switch (confidence) {
            case HIGH -> CodeSearchAuditStore.CONFIDENCE_HIGH;
            case MEDIUM -> CodeSearchAuditStore.CONFIDENCE_MEDIUM;
            case LOW -> CodeSearchAuditStore.CONFIDENCE_LOW;
            case NONE -> null;
        };
    }

    private String auditType(String queryType) {
        if (queryType.contains("direct_call")) {
            return CodeSearchAuditStore.TYPE_CALL_SITE;
        }
        if (queryType.contains("context")) {
            return CodeSearchAuditStore.TYPE_CONTEXT;
        }
        return CodeSearchAuditStore.TYPE_SYMBOL;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface ServiceOperation {
        ServiceQuery run() throws Exception;
    }

    private record ServiceQuery(
            SymbolLookupResult lookup,
            CallSiteResult callSites,
            CodeContext context,
            CodeIndexResult refreshResult
    ) {
        int resultCount() {
            if (context != null) {
                return context.sections().size();
            }
            if (callSites != null) {
                return callSites.resultCount();
            }
            return lookup == null ? 0 : lookup.resultCount();
        }

        boolean ambiguous() {
            return (lookup != null && lookup.ambiguous())
                    || (callSites != null && callSites.ambiguous());
        }

        ConfidenceLevel confidence() {
            if (callSites != null) {
                return callSites.confidence();
            }
            return lookup == null ? ConfidenceLevel.NONE : lookup.confidence();
        }
    }
}
