package com.paicli.codeintelligence;

public record MethodSymbol(
        String fileId,
        String relativePath,
        String filePackageName,
        String importsJson,
        String classId,
        String className,
        String qualifiedName,
        String methodId,
        String methodName,
        String kind,
        String signature,
        String returnType,
        String parameterTypes,
        Integer startLine,
        Integer endLine,
        String chunkText,
        String chunkSha256,
        String modifiers
) {
}
