package com.paicli.codeintelligence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.paicli.state.CodeIndexStore;
import com.paicli.state.CodeSearchAuditStore;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Heuristic direct call-site search over indexed project Java source chunks.
 * This is bounded evidence, not a full call graph or type-solved analysis.
 */
public class DirectCallSiteSearch {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ISSUE_CHARS = 1000;

    private final CodeIndexStore codeIndex;
    private final SymbolLookupService symbols;
    private final CodeSearchAuditStore audits;
    private final JavaParser parser;

    public DirectCallSiteSearch(CodeIndexStore codeIndex,
                                SymbolLookupService symbols,
                                CodeSearchAuditStore audits) {
        this.codeIndex = codeIndex;
        this.symbols = symbols;
        this.audits = audits;
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(configuration);
    }

    public CallSiteResult search(MethodSymbol target) throws SQLException {
        Objects.requireNonNull(target, "target");
        return withAudit("method.symbol", target.qualifiedName() + "#"
                + target.signature(), () -> searchForTargets(List.of(target)));
    }

    public CallSiteResult search(String classNameOrQualifiedName,
                                 String methodName) throws SQLException {
        return withAudit("method.class_name",
                classNameOrQualifiedName + "#" + methodName, () -> {
                    SymbolLookupResult lookup = symbols.lookupMethodsByClassAndName(
                            classNameOrQualifiedName, methodName);
                    if (lookup.methods().isEmpty()) {
                        return new CallSiteResult(ResolutionStatus.NOT_FOUND,
                                ConfidenceLevel.NONE, List.of(), List.of(),
                                List.of(), "No target method candidates found.");
                    }
                    return searchForTargets(lookup.methods());
                });
    }

    private CallSiteResult searchForTargets(List<MethodSymbol> targets)
            throws SQLException {
        if (targets.isEmpty()) {
            return new CallSiteResult(ResolutionStatus.NOT_FOUND,
                    ConfidenceLevel.NONE, List.of(), List.of(), List.of(),
                    "No target method candidates found.");
        }
        String methodName = targets.getFirst().methodName();
        List<CallSiteCandidate> callSites = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        for (CodeIndexStore.CodeFileRecord file :
                codeIndex.findFilesByLanguage("java")) {
            if (!isSearchable(file.status())) {
                continue;
            }
            Map<String, CodeIndexStore.ClassRecord> classesById =
                    codeIndex.findClassesByFile(file.fileId()).stream()
                            .collect(Collectors.toMap(
                                    CodeIndexStore.ClassRecord::classId,
                                    Function.identity(), (left, right) -> left));
            for (CodeIndexStore.MethodRecord caller :
                    codeIndex.findMethodsByFile(file.fileId())) {
                CodeIndexStore.ClassRecord callerClass =
                        classesById.get(caller.classId());
                if (callerClass == null) {
                    issues.add("Missing caller class for method: "
                            + caller.methodId());
                    continue;
                }
                parseCalls(caller, callerClass, file, methodName, targets,
                        callSites, issues);
            }
        }

        callSites.sort(Comparator
                .comparing(CallSiteCandidate::callerFilePath)
                .thenComparing(CallSiteCandidate::callLine)
                .thenComparing(CallSiteCandidate::callerClass)
                .thenComparing(CallSiteCandidate::callerMethod)
                .thenComparing(CallSiteCandidate::callExpressionText));

        if (callSites.isEmpty()) {
            return new CallSiteResult(ResolutionStatus.NOT_FOUND,
                    issues.isEmpty() ? ConfidenceLevel.NONE : ConfidenceLevel.NONE,
                    targets, List.of(), issues,
                    issues.isEmpty() ? "No direct call sites found."
                            : "No direct call sites found; some caller chunks failed to parse.");
        }

        boolean ambiguous = callSites.stream().anyMatch(candidate ->
                candidate.resolutionStatus() == ResolutionStatus.AMBIGUOUS
                        || candidate.resolutionStatus() == ResolutionStatus.MULTIPLE_CANDIDATES);
        return new CallSiteResult(ambiguous ? ResolutionStatus.AMBIGUOUS
                : ResolutionStatus.BOUNDED_HEURISTIC,
                maxConfidence(callSites), targets, callSites, issues,
                "Direct call-site heuristic matched indexed method chunks.");
    }

    private void parseCalls(CodeIndexStore.MethodRecord caller,
                            CodeIndexStore.ClassRecord callerClass,
                            CodeIndexStore.CodeFileRecord file,
                            String methodName,
                            List<MethodSymbol> targets,
                            List<CallSiteCandidate> callSites,
                            List<String> issues) {
        String wrapper = "class " + safeWrapperName(callerClass.className())
                + " {\n" + (caller.chunkText() == null ? "" : caller.chunkText())
                + "\n}";
        ParseResult<CompilationUnit> result = parser.parse(wrapper);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            issues.add(excerpt("PARSE_ERROR " + caller.methodId() + ": "
                    + result.getProblems()));
            return;
        }
        CompilationUnit compilationUnit = result.getResult().orElseThrow();
        for (MethodCallExpr call : compilationUnit.findAll(MethodCallExpr.class)) {
            if (!methodName.equals(call.getNameAsString())) {
                continue;
            }
            List<MethodSymbol> arityMatches = targets.stream()
                    .filter(target -> arity(target) == call.getArguments().size())
                    .toList();
            if (arityMatches.isEmpty()) {
                continue;
            }
            callSites.add(toCandidate(file, caller, callerClass, call,
                    arityMatches));
        }
    }

    private CallSiteCandidate toCandidate(CodeIndexStore.CodeFileRecord file,
                                          CodeIndexStore.MethodRecord caller,
                                          CodeIndexStore.ClassRecord callerClass,
                                          MethodCallExpr call,
                                          List<MethodSymbol> arityMatches) {
        LinkedHashSet<CallSiteEvidence> evidence = new LinkedHashSet<>();
        evidence.add(CallSiteEvidence.METHOD_NAME_MATCH);
        evidence.add(CallSiteEvidence.ARGUMENT_ARITY_MATCH);

        List<MethodSymbol> scopedTargets = scopeTargets(file, callerClass,
                call, arityMatches, evidence);
        ResolutionStatus status = candidateStatus(scopedTargets, evidence);
        ConfidenceLevel confidence = candidateConfidence(status, evidence);
        return new CallSiteCandidate(file.relativePath(),
                callerClass.qualifiedName(), caller.methodName(), caller.kind(),
                caller.signature(), call.toString(), absoluteLine(caller, call),
                call.getArguments().size(), status, confidence,
                List.copyOf(evidence), scopedTargets);
    }

    private List<MethodSymbol> scopeTargets(CodeIndexStore.CodeFileRecord file,
                                            CodeIndexStore.ClassRecord callerClass,
                                            MethodCallExpr call,
                                            List<MethodSymbol> arityMatches,
                                            Set<CallSiteEvidence> evidence) {
        if (call.getScope().isEmpty()) {
            List<MethodSymbol> sameClass = arityMatches.stream()
                    .filter(target -> target.qualifiedName()
                            .equals(callerClass.qualifiedName()))
                    .toList();
            if (!sameClass.isEmpty()) {
                evidence.add(CallSiteEvidence.SAME_CLASS_UNQUALIFIED_CALL);
                return sameClass;
            }
            evidence.add(CallSiteEvidence.UNKNOWN_RECEIVER);
            return arityMatches;
        }

        Expression scope = call.getScope().orElseThrow();
        String scopeText = scope.toString();
        if ("this".equals(scopeText)) {
            List<MethodSymbol> sameClass = arityMatches.stream()
                    .filter(target -> target.qualifiedName()
                            .equals(callerClass.qualifiedName()))
                    .toList();
            if (!sameClass.isEmpty()) {
                evidence.add(CallSiteEvidence.SAME_CLASS_UNQUALIFIED_CALL);
                return sameClass;
            }
            evidence.add(CallSiteEvidence.UNKNOWN_RECEIVER);
            return arityMatches;
        }

        List<MethodSymbol> classScope = arityMatches.stream()
                .filter(target -> scopeMatchesClass(file, scopeText, target, evidence))
                .toList();
        if (!classScope.isEmpty()) {
            evidence.add(CallSiteEvidence.STATIC_CLASS_SCOPE_MATCH);
            if (classScope.size() > 1) {
                evidence.add(CallSiteEvidence.CLASS_AMBIGUOUS);
            }
            return classScope;
        }

        evidence.add(CallSiteEvidence.UNKNOWN_RECEIVER);
        return arityMatches;
    }

    private boolean scopeMatchesClass(CodeIndexStore.CodeFileRecord file,
                                      String scopeText,
                                      MethodSymbol target,
                                      Set<CallSiteEvidence> evidence) {
        if (scopeText.equals(target.qualifiedName())) {
            return true;
        }
        if (!scopeText.equals(target.className())) {
            return false;
        }
        if (imports(file.importsJson()).contains(target.qualifiedName())) {
            evidence.add(CallSiteEvidence.IMPORT_MATCH);
            return true;
        }
        if (Objects.equals(file.packageName(), packageName(target.qualifiedName()))) {
            evidence.add(CallSiteEvidence.PACKAGE_MATCH);
            return true;
        }
        return true;
    }

    private ResolutionStatus candidateStatus(List<MethodSymbol> candidates,
                                             Set<CallSiteEvidence> evidence) {
        if (candidates.size() > 1) {
            if (sameArityOverload(candidates)) {
                evidence.add(CallSiteEvidence.OVERLOAD_AMBIGUOUS);
            }
            if (sameSimpleClassDifferentQualifiedName(candidates)) {
                evidence.add(CallSiteEvidence.CLASS_AMBIGUOUS);
            }
            return ResolutionStatus.AMBIGUOUS;
        }
        return ResolutionStatus.BOUNDED_HEURISTIC;
    }

    private ConfidenceLevel candidateConfidence(ResolutionStatus status,
                                                Set<CallSiteEvidence> evidence) {
        if (status == ResolutionStatus.AMBIGUOUS
                || evidence.contains(CallSiteEvidence.UNKNOWN_RECEIVER)) {
            return ConfidenceLevel.LOW;
        }
        if (evidence.contains(CallSiteEvidence.SAME_CLASS_UNQUALIFIED_CALL)
                || evidence.contains(CallSiteEvidence.STATIC_CLASS_SCOPE_MATCH)) {
            return ConfidenceLevel.HIGH;
        }
        if (evidence.contains(CallSiteEvidence.IMPORT_MATCH)
                || evidence.contains(CallSiteEvidence.PACKAGE_MATCH)) {
            return ConfidenceLevel.MEDIUM;
        }
        return ConfidenceLevel.LOW;
    }

    private boolean sameArityOverload(List<MethodSymbol> candidates) {
        return candidates.stream().map(this::arity).distinct().count() == 1
                && candidates.stream().map(MethodSymbol::signature)
                .distinct().count() > 1;
    }

    private boolean sameSimpleClassDifferentQualifiedName(
            List<MethodSymbol> candidates) {
        return candidates.stream().map(MethodSymbol::className).distinct().count() == 1
                && candidates.stream().map(MethodSymbol::qualifiedName)
                .distinct().count() > 1;
    }

    private int absoluteLine(CodeIndexStore.MethodRecord caller, Node node) {
        int relativeLine = node.getRange().map(range -> range.begin.line).orElse(1);
        return caller.startLine() == null ? relativeLine
                : caller.startLine() + relativeLine - 2;
    }

    private String safeWrapperName(String className) {
        if (className == null || className.isBlank()) {
            return "__PaiCliChunkWrapper";
        }
        String value = className.replaceAll("[^A-Za-z0-9_$]", "_");
        if (value.isBlank() || Character.isDigit(value.charAt(0))) {
            return "__PaiCliChunkWrapper";
        }
        return value;
    }

    private int arity(MethodSymbol method) {
        return arity(method.parameterTypes());
    }

    private int arity(String parameterTypesJson) {
        if (parameterTypesJson == null || parameterTypesJson.isBlank()) {
            return 0;
        }
        try {
            return MAPPER.readValue(parameterTypesJson,
                    new TypeReference<List<String>>() {
                    }).size();
        } catch (JsonProcessingException e) {
            return -1;
        }
    }

    private List<String> imports(String importsJson) {
        if (importsJson == null || importsJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(importsJson,
                    new TypeReference<List<String>>() {
                    });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String packageName(String qualifiedName) {
        int index = qualifiedName == null ? -1 : qualifiedName.lastIndexOf('.');
        return index < 0 ? null : qualifiedName.substring(0, index);
    }

    private boolean isSearchable(String status) {
        return CodeIndexStore.STATUS_INDEXED.equals(status)
                || CodeIndexStore.STATUS_UNCHANGED.equals(status);
    }

    private ConfidenceLevel maxConfidence(List<CallSiteCandidate> candidates) {
        if (candidates.stream().anyMatch(candidate ->
                candidate.confidence() == ConfidenceLevel.HIGH)) {
            return ConfidenceLevel.HIGH;
        }
        if (candidates.stream().anyMatch(candidate ->
                candidate.confidence() == ConfidenceLevel.MEDIUM)) {
            return ConfidenceLevel.MEDIUM;
        }
        if (candidates.stream().anyMatch(candidate ->
                candidate.confidence() == ConfidenceLevel.LOW)) {
            return ConfidenceLevel.LOW;
        }
        return ConfidenceLevel.NONE;
    }

    private CallSiteResult withAudit(String searchType, String queryInput,
                                     SearchOperation operation)
            throws SQLException {
        String auditId = UUID.randomUUID().toString();
        String auditInput = "{\"search\":\"" + escape(searchType)
                + "\",\"input\":\"" + escape(queryInput) + "\"}";
        Instant started = Instant.now();
        audits.startAudit(auditId, CodeSearchAuditStore.TYPE_CALL_SITE,
                auditInput);
        try {
            CallSiteResult result = operation.run();
            audits.completeAudit(auditId, result.resultCount(),
                    result.ambiguous(), toAuditConfidence(result.confidence()),
                    null, Duration.between(started, Instant.now()).toMillis());
            return result;
        } catch (SQLException | RuntimeException e) {
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

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String excerpt(String value) {
        if (value == null || value.length() <= MAX_ISSUE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ISSUE_CHARS);
    }

    @FunctionalInterface
    private interface SearchOperation {
        CallSiteResult run() throws SQLException;
    }
}
