package com.paicli.interactive.query;

import com.paicli.interactive.diff.PreWriteDiff;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveDiffQueryTest {
    @TempDir Path temp;

    @Test void distinguishesProposalFromAppliedChangeAndUsesLastTask() {
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        InteractiveSession session = new InteractiveSession("session", temp,
                "ollama", "model", "on-request",
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start();
        session.startTask("task", "run");
        view.taskStarted("task", "run");
        view.proposal(new PreWriteDiff("src/A.java", "a".repeat(64), "b".repeat(64),
                2, 1, List.of("--- a/src/A.java", "+++ b/src/A.java", "-old", "+new"),
                false, false, true));
        StateStoreInteractiveQueryFacade query = new StateStoreInteractiveQueryFacade(temp, view);
        List<String> proposed = query.diff(session.snapshot());
        assertEquals("No current effective changes.", proposed.getFirst());
        assertTrue(proposed.contains("Pending proposals:"));
        assertTrue(proposed.stream().anyMatch(line -> line.startsWith("PROPOSED src/A.java ")));
        view.applied("src/A.java", "snapshot-safe-ref");
        List<String> applied = query.diff(session.snapshot());
        assertTrue(applied.getFirst().startsWith("APPLIED "));
        assertTrue(applied.contains("Snapshot: present"));
        assertFalse(String.join("\n", applied).contains(temp.toAbsolutePath().toString()));

        view.snapshotRewound("task", "src/A.java");
        List<String> rewound = query.diff(session.snapshot());
        assertEquals("No current effective changes.", rewound.getFirst());
        assertTrue(rewound.stream().anyMatch(line -> line.startsWith("REWOUND (historical) ")));
        assertFalse(rewound.stream().anyMatch(line -> line.startsWith("APPLIED ")));
    }

    @Test void reportsNoneWithoutChanges() {
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        InteractiveSession session = new InteractiveSession("session", temp,
                "ollama", "model", "read-only",
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start();
        assertEquals(List.of("No proposed or applied changes."),
                new StateStoreInteractiveQueryFacade(temp, view).diff(session.snapshot()));
    }

    @Test void rejectedAndCancelledProposalsRemainHistoricalNotSuperseded() {
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        InteractiveSession session = new InteractiveSession("session", temp,
                "ollama", "model", "on-request",
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start();
        session.startTask("task", "run");
        view.taskStarted("task", "run");
        PreWriteDiff rejected = new PreWriteDiff("src/A.java", "a".repeat(64),
                "b".repeat(64), 1, 1, List.of("-old", "+first"),
                false, false, true);
        view.proposal(rejected);
        view.proposalResolved("src/A.java", rejected.afterHash(), "REJECTED");
        PreWriteDiff cancelled = new PreWriteDiff("src/A.java", "a".repeat(64),
                "c".repeat(64), 1, 1, List.of("-old", "+second"),
                false, false, true);
        view.proposal(cancelled);
        view.proposalResolved("src/A.java", cancelled.afterHash(), "CANCELLED");



        List<String> lines = new StateStoreInteractiveQueryFacade(temp, view)
                .diff(session.snapshot());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("REJECTED (historical) ")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("CANCELLED (historical) ")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("SUPERSEDED (historical) ")));
    }
}
