package com.paicli.codeintelligence;

public record CodeContextBudget(
        int maxTotalChars,
        int maxChunkChars,
        int maxCallers,
        int maxFields,
        int maxCandidates
) {
    public static CodeContextBudget defaults() {
        return new CodeContextBudget(12_000, 4_000, 10, 20, 10);
    }
}
