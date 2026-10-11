package com.paicli.codeintelligence;

public record ClassSymbol(
        String fileId,
        String relativePath,
        String filePackageName,
        String importsJson,
        String classId,
        String packageName,
        String className,
        String qualifiedName,
        String kind,
        Integer startLine,
        Integer endLine,
        String modifiers,
        String extendsName,
        String implementsNames
) {
}
