package com.paicli.codeintelligence;

import com.paicli.codeindex.CodeIndexResult;

public record CodeIntelligenceResult(
        SymbolLookupResult lookup,
        CallSiteResult callSites,
        CodeContext context,
        CodeIndexResult refreshResult
) {
}
