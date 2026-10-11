package com.paicli.codeintelligence;

import java.util.List;

public record SymbolLookupResult(
        ResolutionStatus status,
        ConfidenceLevel confidence,
        List<ClassSymbol> classes,
        List<MethodSymbol> methods,
        List<FieldSymbol> fields,
        String message
) {
    public int resultCount() {
        return classes.size() + methods.size() + fields.size();
    }

    public boolean ambiguous() {
        return status == ResolutionStatus.AMBIGUOUS
                || status == ResolutionStatus.MULTIPLE_CANDIDATES;
    }
}
