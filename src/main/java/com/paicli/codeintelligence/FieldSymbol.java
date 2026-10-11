package com.paicli.codeintelligence;

public record FieldSymbol(
        String fileId,
        String relativePath,
        String filePackageName,
        String importsJson,
        String classId,
        String className,
        String qualifiedName,
        String fieldId,
        String fieldName,
        String fieldType,
        Integer startLine,
        Integer endLine,
        String modifiers
) {
}
