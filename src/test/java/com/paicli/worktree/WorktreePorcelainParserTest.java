package com.paicli.worktree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorktreePorcelainParserTest {
    private final WorktreePorcelainParser parser = new WorktreePorcelainParser();

    @Test
    void parsesNulDelimitedMultipleUnicodeAndSpacePaths() throws Exception {
        String input = "worktree C:/repo main\0HEAD " + "a".repeat(40)
                + "\0branch refs/heads/main\0\0"
                + "worktree C:/workers/工作 tree\0HEAD " + "b".repeat(40)
                + "\0branch refs/heads/paicli/worker/w1\0locked runtime\0\0";
        List<WorktreePorcelainParser.Entry> entries = parser.parse(input);
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).path().toString().contains("repo main"));
        assertTrue(entries.get(1).path().toString().contains("工作 tree"));
        assertTrue(entries.get(1).locked());
        assertEquals("runtime", entries.get(1).lockReason());
    }

    @Test
    void recognizesDetachedLockedAndPrunableRecords() throws Exception {
        String input = "worktree C:/detached\0HEAD " + "c".repeat(40)
                + "\0detached\0locked\0prunable stale metadata\0\0";
        WorktreePorcelainParser.Entry entry = parser.parse(input).get(0);
        assertTrue(entry.detached());
        assertTrue(entry.locked());
        assertTrue(entry.prunable());
        assertEquals("stale metadata", entry.pruneReason());
    }

    @Test
    void malformedOutputFailsClosed() {
        assertThrows(ManagedWorktreeException.class,
                () -> parser.parse("HEAD " + "a".repeat(40) + "\0"));
        assertThrows(ManagedWorktreeException.class,
                () -> parser.parse("worktree C:/repo\0unknown value\0\0"));
        assertThrows(ManagedWorktreeException.class,
                () -> parser.parse("worktree C:/repo\0branch refs/heads/main\0\0"));
    }
}
