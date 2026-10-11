package com.paicli.codeintelligence;

import com.paicli.state.CodeIndexStore;
import com.paicli.state.CodeSearchAuditStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Builds bounded deterministic code context from indexed code evidence. */
public class CodeContextBuilder {

    private final CodeIndexStore codeIndex;
    private final CodeContextBudget budget;

    public CodeContextBuilder(CodeIndexStore codeIndex) {
        this(codeIndex, CodeContextBudget.defaults());
    }

    public CodeContextBuilder(CodeIndexStore codeIndex,
                              CodeContextBudget budget) {
        this.codeIndex = codeIndex;
        this.budget = budget;
    }

    public CodeContext buildForClass(ClassSymbol target)
            throws SQLException {
        List<CodeContextSection> sections = new ArrayList<>();
        add(sections, "target", classMetadata(target), 0);
        add(sections, "fields", fieldsForClass(target.classId()), 0);
        return finalizeContext(sections);
    }

    public CodeContext buildForMethod(MethodSymbol target,
                                      CallSiteResult callSites)
            throws SQLException {
        List<CodeContextSection> sections = new ArrayList<>();
        add(sections, "target", methodMetadata(target), 0);
        add(sections, "fields", fieldsForClass(target.classId()), 0);
        add(sections, "method_chunk", target.chunkText(), budget.maxChunkChars());
        if (callSites != null) {
            add(sections, "callers", callerSummary(callSites), 0);
        }
        return finalizeContext(sections);
    }

    public CodeContext buildForLookup(SymbolLookupResult lookup,
                                      CallSiteResult callSites)
            throws SQLException {
        List<CodeContextSection> sections = new ArrayList<>();
        add(sections, "lookup", lookupMetadata(lookup), 0);
        for (ClassSymbol clazz : lookup.classes().stream()
                .limit(budget.maxCandidates()).toList()) {
            add(sections, "class:" + clazz.qualifiedName(),
                    classMetadata(clazz), 0);
        }
        for (MethodSymbol method : lookup.methods().stream()
                .limit(budget.maxCandidates()).toList()) {
            add(sections, "method:" + method.qualifiedName() + "#"
                    + method.signature(), methodMetadata(method), 0);
            add(sections, "method_chunk:" + method.methodId(),
                    method.chunkText(), budget.maxChunkChars());
        }
        for (FieldSymbol field : lookup.fields().stream()
                .limit(budget.maxCandidates()).toList()) {
            add(sections, "field:" + field.qualifiedName() + "#"
                    + field.fieldName(), fieldMetadata(field), 0);
        }
        if (callSites != null) {
            add(sections, "callers", callerSummary(callSites), 0);
        }
        return finalizeContext(sections);
    }

    private String classMetadata(ClassSymbol symbol) {
        return String.join("\n",
                "kind=class",
                "file=" + symbol.relativePath(),
                "package=" + nullToEmpty(symbol.packageName()),
                "imports=" + nullToEmpty(symbol.importsJson()),
                "class=" + symbol.qualifiedName(),
                "class_kind=" + symbol.kind(),
                "lines=" + symbol.startLine() + "-" + symbol.endLine(),
                "modifiers=" + nullToEmpty(symbol.modifiers()),
                "extends=" + nullToEmpty(symbol.extendsName()),
                "implements=" + nullToEmpty(symbol.implementsNames()));
    }

    private String methodMetadata(MethodSymbol symbol) {
        return String.join("\n",
                "kind=method",
                "file=" + symbol.relativePath(),
                "package=" + nullToEmpty(symbol.filePackageName()),
                "imports=" + nullToEmpty(symbol.importsJson()),
                "class=" + symbol.qualifiedName(),
                "method=" + symbol.methodName(),
                "method_kind=" + symbol.kind(),
                "signature=" + symbol.signature(),
                "return_type=" + nullToEmpty(symbol.returnType()),
                "parameter_types=" + nullToEmpty(symbol.parameterTypes()),
                "lines=" + symbol.startLine() + "-" + symbol.endLine(),
                "chunk_sha256=" + nullToEmpty(symbol.chunkSha256()));
    }

    private String fieldMetadata(FieldSymbol symbol) {
        return String.join("\n",
                "kind=field",
                "file=" + symbol.relativePath(),
                "class=" + symbol.qualifiedName(),
                "field=" + symbol.fieldName(),
                "type=" + symbol.fieldType(),
                "lines=" + symbol.startLine() + "-" + symbol.endLine(),
                "modifiers=" + nullToEmpty(symbol.modifiers()));
    }

    private String lookupMetadata(SymbolLookupResult lookup) {
        return String.join("\n",
                "status=" + lookup.status(),
                "confidence=" + lookup.confidence(),
                "result_count=" + lookup.resultCount(),
                "ambiguous=" + lookup.ambiguous(),
                "message=" + nullToEmpty(lookup.message()));
    }

    private String fieldsForClass(String classId) throws SQLException {
        List<String> lines = new ArrayList<>();
        for (CodeIndexStore.FieldQueryRecord field :
                codeIndex.findFieldsByClass(classId).stream()
                        .limit(budget.maxFields()).toList()) {
            lines.add(field.fieldName() + ":" + field.fieldType()
                    + " lines=" + field.startLine() + "-" + field.endLine()
                    + " modifiers=" + nullToEmpty(field.modifiers()));
        }
        return String.join("\n", lines);
    }

    private String callerSummary(CallSiteResult callSites) {
        List<String> lines = new ArrayList<>();
        lines.add("status=" + callSites.status());
        lines.add("confidence=" + callSites.confidence());
        lines.add("result_count=" + callSites.resultCount());
        lines.add("ambiguous=" + callSites.ambiguous());
        for (CallSiteCandidate candidate : callSites.callSites().stream()
                .limit(budget.maxCallers()).toList()) {
            lines.add(candidate.callerFilePath() + ":"
                    + candidate.callLine() + " "
                    + candidate.callerClass() + "#"
                    + candidate.callerMethod() + " -> "
                    + candidate.callExpressionText()
                    + " confidence=" + candidate.confidence()
                    + " evidence=" + candidate.evidence());
        }
        return String.join("\n", lines);
    }

    private void add(List<CodeContextSection> sections, String title,
                     String content, int sectionBudget) {
        String value = content == null ? "" : content;
        if (value.isBlank()) {
            return;
        }
        int maxChars = sectionBudget > 0 ? sectionBudget : Integer.MAX_VALUE;
        boolean truncated = value.length() > maxChars;
        sections.add(new CodeContextSection(title,
                truncated ? value.substring(0, maxChars) : value,
                truncated));
    }

    private CodeContext finalizeContext(List<CodeContextSection> sections) {
        List<CodeContextSection> bounded = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        for (CodeContextSection section : sections) {
            String rendered = render(section);
            int remaining = budget.maxTotalChars() - total;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (rendered.length() > remaining) {
                int contentBudget = remaining - header(section).length() - 1;
                if (contentBudget <= 0) {
                    truncated = true;
                    break;
                }
                bounded.add(new CodeContextSection(section.title(),
                        section.content().substring(0, Math.min(
                                section.content().length(), contentBudget)),
                        true));
                total = budget.maxTotalChars();
                truncated = true;
                break;
            }
            bounded.add(section);
            total += rendered.length();
            truncated = truncated || section.truncated();
        }
        String rendered = bounded.stream()
                .map(this::render)
                .reduce("", (left, right) -> left + right);
        return new CodeContext(List.copyOf(bounded), truncated,
                CodeSearchAuditStore.sha256(rendered), rendered.length());
    }

    private String render(CodeContextSection section) {
        return header(section) + section.content() + "\n";
    }

    private String header(CodeContextSection section) {
        return "## " + section.title() + "\n";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
