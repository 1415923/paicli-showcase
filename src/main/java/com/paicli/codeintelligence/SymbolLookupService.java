package com.paicli.codeintelligence;

import com.paicli.state.CodeIndexStore;
import com.paicli.state.CodeSearchAuditStore;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Deterministic symbol lookup over the current SQLite code index. */
public class SymbolLookupService {

    private final CodeIndexStore codeIndex;
    private final CodeSearchAuditStore audits;

    public SymbolLookupService(CodeIndexStore codeIndex,
                               CodeSearchAuditStore audits) {
        this.codeIndex = codeIndex;
        this.audits = audits;
    }

    public SymbolLookupResult lookupClassBySimpleName(String name)
            throws SQLException {
        return withAudit("class.simple", name, () -> {
            List<ClassSymbol> classes = codeIndex.findClassesBySimpleName(name)
                    .stream().map(this::toClassSymbol).toList();
            if (classes.isEmpty()) {
                return notFound("No class found for simple name: " + name);
            }
            if (classes.size() == 1) {
                return classResult(ResolutionStatus.EXACT, ConfidenceLevel.HIGH,
                        classes, "One class matched the simple name.");
            }
            return classResult(ResolutionStatus.MULTIPLE_CANDIDATES,
                    ConfidenceLevel.MEDIUM, classes,
                    "Multiple classes matched the simple name.");
        });
    }

    public SymbolLookupResult lookupClassByQualifiedName(String qualifiedName)
            throws SQLException {
        return withAudit("class.qualified", qualifiedName, () -> {
            Optional<CodeIndexStore.ClassQueryRecord> record =
                    codeIndex.findClassByQualifiedName(qualifiedName);
            if (record.isEmpty()) {
                return notFound("No class found for qualified name: " + qualifiedName);
            }
            return classResult(ResolutionStatus.EXACT, ConfidenceLevel.HIGH,
                    List.of(toClassSymbol(record.get())),
                    "Qualified class name matched exactly.");
        });
    }

    public SymbolLookupResult lookupMethodsByName(String methodName)
            throws SQLException {
        return withAudit("method.name", methodName, () -> {
            List<MethodSymbol> methods = codeIndex.findMethodsByName(methodName)
                    .stream().map(this::toMethodSymbol).toList();
            if (methods.isEmpty()) {
                return notFound("No method found for name: " + methodName);
            }
            return methodResult(methods.size() == 1
                            ? ResolutionStatus.EXACT
                            : ResolutionStatus.MULTIPLE_CANDIDATES,
                    methods.size() == 1 ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                    methods, methods.size() == 1
                            ? "One method matched the name."
                            : "Multiple methods matched the name.");
        });
    }

    public SymbolLookupResult lookupMethodsByClassAndName(
            String classNameOrQualifiedName, String methodName) throws SQLException {
        return withAudit("method.class_name",
                classNameOrQualifiedName + "#" + methodName, () -> {
                    List<ClassSymbol> classes = resolveClasses(classNameOrQualifiedName);
                    if (classes.isEmpty()) {
                        return notFound("No class found for: " + classNameOrQualifiedName);
                    }
                    List<MethodSymbol> methods = new ArrayList<>();
                    for (ClassSymbol clazz : classes) {
                        methods.addAll(codeIndex.findMethodsByClassAndName(
                                        clazz.classId(), methodName)
                                .stream().map(this::toMethodSymbol).toList());
                    }
                    if (methods.isEmpty()) {
                        return notFound("No method found for class and name: "
                                + classNameOrQualifiedName + "#" + methodName);
                    }
                    if (classes.size() > 1) {
                        return methodResult(ResolutionStatus.AMBIGUOUS,
                                ConfidenceLevel.LOW, methods,
                                "Simple class name matched multiple classes.");
                    }
                    if (methods.size() > 1) {
                        return methodResult(ResolutionStatus.MULTIPLE_CANDIDATES,
                                ConfidenceLevel.MEDIUM, methods,
                                "Method name matched multiple overloads.");
                    }
                    return methodResult(ResolutionStatus.EXACT, ConfidenceLevel.HIGH,
                            methods, "Class and method name matched exactly.");
                });
    }

    public SymbolLookupResult lookupFieldsByClass(String classNameOrQualifiedName)
            throws SQLException {
        return withAudit("field.class", classNameOrQualifiedName, () -> {
            List<ClassSymbol> classes = resolveClasses(classNameOrQualifiedName);
            if (classes.isEmpty()) {
                return notFound("No class found for: " + classNameOrQualifiedName);
            }
            List<FieldSymbol> fields = new ArrayList<>();
            for (ClassSymbol clazz : classes) {
                fields.addAll(codeIndex.findFieldsByClass(clazz.classId())
                        .stream().map(this::toFieldSymbol).toList());
            }
            if (fields.isEmpty()) {
                return notFound("No fields found for class: " + classNameOrQualifiedName);
            }
            return fieldResult(classes.size() > 1
                            ? ResolutionStatus.AMBIGUOUS
                            : ResolutionStatus.MULTIPLE_CANDIDATES,
                    classes.size() > 1 ? ConfidenceLevel.LOW : ConfidenceLevel.MEDIUM,
                    fields, classes.size() > 1
                            ? "Simple class name matched multiple classes."
                            : "Fields matched the class.");
        });
    }

    public SymbolLookupResult lookupFieldsByClassAndName(
            String classNameOrQualifiedName, String fieldName) throws SQLException {
        return withAudit("field.class_name",
                classNameOrQualifiedName + "#" + fieldName, () -> {
                    List<ClassSymbol> classes = resolveClasses(classNameOrQualifiedName);
                    if (classes.isEmpty()) {
                        return notFound("No class found for: " + classNameOrQualifiedName);
                    }
                    List<FieldSymbol> fields = new ArrayList<>();
                    for (ClassSymbol clazz : classes) {
                        fields.addAll(codeIndex.findFieldsByClassAndName(
                                        clazz.classId(), fieldName)
                                .stream().map(this::toFieldSymbol).toList());
                    }
                    if (fields.isEmpty()) {
                        return notFound("No field found for class and name: "
                                + classNameOrQualifiedName + "#" + fieldName);
                    }
                    if (classes.size() > 1) {
                        return fieldResult(ResolutionStatus.AMBIGUOUS,
                                ConfidenceLevel.LOW, fields,
                                "Simple class name matched multiple classes.");
                    }
                    if (fields.size() > 1) {
                        return fieldResult(ResolutionStatus.MULTIPLE_CANDIDATES,
                                ConfidenceLevel.MEDIUM, fields,
                                "Multiple fields matched the class and name.");
                    }
                    return fieldResult(ResolutionStatus.EXACT, ConfidenceLevel.HIGH,
                            fields, "Class and field name matched exactly.");
                });
    }

    private List<ClassSymbol> resolveClasses(String classNameOrQualifiedName)
            throws SQLException {
        if (classNameOrQualifiedName.contains(".")) {
            return codeIndex.findClassByQualifiedName(classNameOrQualifiedName)
                    .map(record -> List.of(toClassSymbol(record)))
                    .orElseGet(List::of);
        }
        return codeIndex.findClassesBySimpleName(classNameOrQualifiedName)
                .stream().map(this::toClassSymbol).toList();
    }

    private SymbolLookupResult withAudit(String lookupType, String queryInput,
                                         LookupOperation operation)
            throws SQLException {
        String auditId = UUID.randomUUID().toString();
        String auditInput = "{\"lookup\":\"" + escape(lookupType)
                + "\",\"input\":\"" + escape(queryInput) + "\"}";
        Instant started = Instant.now();
        audits.startAudit(auditId, CodeSearchAuditStore.TYPE_SYMBOL, auditInput);
        try {
            SymbolLookupResult result = operation.run();
            audits.completeAudit(auditId, result.resultCount(), result.ambiguous(),
                    toAuditConfidence(result.confidence()), null,
                    Duration.between(started, Instant.now()).toMillis());
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

    private SymbolLookupResult notFound(String message) {
        return new SymbolLookupResult(ResolutionStatus.NOT_FOUND,
                ConfidenceLevel.NONE, List.of(), List.of(), List.of(), message);
    }

    private SymbolLookupResult classResult(ResolutionStatus status,
                                           ConfidenceLevel confidence,
                                           List<ClassSymbol> classes,
                                           String message) {
        return new SymbolLookupResult(status, confidence, classes,
                List.of(), List.of(), message);
    }

    private SymbolLookupResult methodResult(ResolutionStatus status,
                                            ConfidenceLevel confidence,
                                            List<MethodSymbol> methods,
                                            String message) {
        return new SymbolLookupResult(status, confidence, List.of(),
                methods, List.of(), message);
    }

    private SymbolLookupResult fieldResult(ResolutionStatus status,
                                           ConfidenceLevel confidence,
                                           List<FieldSymbol> fields,
                                           String message) {
        return new SymbolLookupResult(status, confidence, List.of(),
                List.of(), fields, message);
    }

    private ClassSymbol toClassSymbol(CodeIndexStore.ClassQueryRecord record) {
        return new ClassSymbol(record.fileId(), record.relativePath(),
                record.filePackageName(), record.importsJson(), record.classId(),
                record.packageName(), record.className(), record.qualifiedName(),
                record.kind(), record.startLine(), record.endLine(),
                record.modifiers(), record.extendsName(), record.implementsNames());
    }

    private MethodSymbol toMethodSymbol(CodeIndexStore.MethodQueryRecord record) {
        return new MethodSymbol(record.fileId(), record.relativePath(),
                record.filePackageName(), record.importsJson(), record.classId(),
                record.className(), record.qualifiedName(), record.methodId(),
                record.methodName(), record.kind(), record.signature(),
                record.returnType(), record.parameterTypes(), record.startLine(),
                record.endLine(), record.chunkText(), record.chunkSha256(),
                record.modifiers());
    }

    private FieldSymbol toFieldSymbol(CodeIndexStore.FieldQueryRecord record) {
        return new FieldSymbol(record.fileId(), record.relativePath(),
                record.filePackageName(), record.importsJson(), record.classId(),
                record.className(), record.qualifiedName(), record.fieldId(),
                record.fieldName(), record.fieldType(), record.startLine(),
                record.endLine(), record.modifiers());
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface LookupOperation {
        SymbolLookupResult run() throws SQLException;
    }
}
