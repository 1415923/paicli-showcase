package com.paicli.codeindex;

import java.util.List;

public record ParsedMethod(
        String className,
        String qualifiedClassName,
        String methodName,
        String kind,
        String signature,
        String returnType,
        List<String> parameterTypes,
        Integer startLine,
        Integer endLine,
        String chunkText,
        String chunkSha256,
        List<String> modifiers
) {
}
