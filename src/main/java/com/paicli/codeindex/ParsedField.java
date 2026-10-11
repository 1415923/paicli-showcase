package com.paicli.codeindex;

import java.util.List;

public record ParsedField(
        String className,
        String qualifiedClassName,
        String fieldName,
        String fieldType,
        List<String> modifiers,
        Integer startLine,
        Integer endLine
) {
}
