package com.paicli.codeindex;

import java.util.List;

public record ParsedClass(
        String packageName,
        String className,
        String qualifiedName,
        String kind,
        List<String> modifiers,
        String extendsName,
        List<String> implementsNames,
        Integer startLine,
        Integer endLine
) {
}
