package com.paicli.codeindex;

import java.util.List;

public record JavaSourceParseResult(
        ParseStatus status,
        String relativePath,
        String packageName,
        List<String> imports,
        List<ParsedClass> classes,
        List<ParsedMethod> methods,
        List<ParsedField> fields,
        String errorExcerpt,
        List<String> unsupportedNotes
) {

    public static JavaSourceParseResult ok(
            String relativePath,
            String packageName,
            List<String> imports,
            List<ParsedClass> classes,
            List<ParsedMethod> methods,
            List<ParsedField> fields,
            List<String> unsupportedNotes) {
        ParseStatus status = unsupportedNotes.isEmpty()
                ? ParseStatus.OK : ParseStatus.OK_WITH_UNSUPPORTED;
        return new JavaSourceParseResult(
                status, relativePath, packageName, List.copyOf(imports),
                List.copyOf(classes), List.copyOf(methods), List.copyOf(fields),
                null, List.copyOf(unsupportedNotes));
    }

    public static JavaSourceParseResult error(
            String relativePath, String errorExcerpt) {
        return new JavaSourceParseResult(
                ParseStatus.ERROR, relativePath, null, List.of(), List.of(),
                List.of(), List.of(), errorExcerpt, List.of());
    }

    public enum ParseStatus {
        OK,
        OK_WITH_UNSUPPORTED,
        ERROR
    }
}
