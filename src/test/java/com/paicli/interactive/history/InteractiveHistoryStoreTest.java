package com.paicli.interactive.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveHistoryStoreTest {
    @TempDir Path temp;

    @Test void persistsUtf8LfMultilineAndDropsOldEntries() throws Exception {
        InteractiveHistoryStore store = new InteractiveHistoryStore(temp.resolve("history"), 3, 256);
        assertTrue(store.append("first"));
        assertFalse(store.append("first"));
        assertTrue(store.append("second line\ncontinued"));
        assertTrue(store.append("中文"));
        assertTrue(store.append("fourth"));
        assertEquals(List.of("second line\ncontinued", "中文", "fourth"), store.load());
        byte[] bytes = Files.readAllBytes(temp.resolve("history/interactive-history"));
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("\r"));
    }

    @Test void refusesSensitiveOversizedAndEnvironmentAssignments() {
        InteractiveHistoryStore store = new InteractiveHistoryStore(temp.resolve("history"));
        assertFalse(store.append("API_KEY=sentinel-secret"));
        assertFalse(store.append("Authorization: Bearer sentinel"));
        assertFalse(store.append("PASSWORD=value"));
        assertFalse(store.append("use sk-abcdefghijklmnopqrstuv now"));
        assertFalse(store.append("eyJabcdefghijklmnopqrstuv.abcdefghijk"));
        assertFalse(store.append("x".repeat(8193)));
        assertEquals(List.of(), store.load());
    }

    @Test void readAndWriteFailuresDisablePersistenceWithoutThrowing() throws Exception {
        Path fileRoot = temp.resolve("not-a-directory");
        Files.writeString(fileRoot, "occupied");
        InteractiveHistoryStore writeFailure = new InteractiveHistoryStore(fileRoot);
        assertFalse(writeFailure.append("safe input"));
        assertFalse(writeFailure.enabled());
        assertEquals("history write failed", writeFailure.warning());

        Path root = temp.resolve("oversized"); Files.createDirectories(root);
        Files.writeString(root.resolve("interactive-history"), "x".repeat(300));
        InteractiveHistoryStore readFailure = new InteractiveHistoryStore(root, 3, 128);
        assertEquals(List.of(), readFailure.load());
        assertFalse(readFailure.enabled());
    }

    @Test void returnsBoundedNewestFirstViewAndHonestlyClearsPersistentStore() {
        InteractiveHistoryStore store = new InteractiveHistoryStore(temp.resolve("bounded"));
        store.append("first"); store.append("second"); store.append("third");
        assertEquals(List.of("third", "second"), store.recent(2));
        assertThrows(IllegalArgumentException.class, () -> store.recent(101));
        assertTrue(store.clear());
        assertEquals(List.of(), store.load());
        assertFalse(Files.exists(temp.resolve("bounded/interactive-history")));
    }
}
