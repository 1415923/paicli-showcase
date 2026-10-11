package com.paicli.state;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstructionManifestCandidateTest {
    private static final String HASH = "a".repeat(64);
    @Test void committedCandidateRequiresDenseAppliedEntriesAndStableHash() {
        var entry = new InstructionManifestCandidate.Entry("e1", "AGENTS", "AGENTS.md", "workspace", InstructionManifestCandidate.Status.APPLIED, 0, HASH, "artifact-1", "OK", null, null);
        var candidate = new InstructionManifestCandidate(1, "v1", List.of(entry));
        candidate.validateForCommit(); assertEquals(candidate.canonicalHash(), candidate.canonicalHash());
    }
    @Test void failedAndNonterminalEntriesBlockCommitAndIgnoredNeedsReason() {
        for (var status : List.of(InstructionManifestCandidate.Status.DISCOVERED, InstructionManifestCandidate.Status.PARSED, InstructionManifestCandidate.Status.FAILED, InstructionManifestCandidate.Status.IGNORED)) {
            var entry = new InstructionManifestCandidate.Entry("e", "AGENTS", "AGENTS.md", "workspace", status, null, null, null, null, null, null);
            assertThrows(IllegalStateException.class, () -> new InstructionManifestCandidate(1, "v1", List.of(entry)).validateForCommit());
        }
        var disguisedFailure = new InstructionManifestCandidate.Entry("e", "AGENTS",
                "AGENTS.md", "workspace", InstructionManifestCandidate.Status.IGNORED,
                null, null, null, null, "out of scope", "read failed");
        assertThrows(IllegalStateException.class,
                () -> new InstructionManifestCandidate(1, "v1", List.of(disguisedFailure))
                        .validateForCommit());
    }
}
