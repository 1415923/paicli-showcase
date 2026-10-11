package com.paicli.interactive.task;

import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.query.InteractiveSnapshotView;
import com.paicli.interactive.query.InteractiveTaskView;
import com.paicli.interactive.session.InteractiveSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveTaskCommandServiceTest {
    @TempDir Path temp;

    @Test void resumeWithoutSelectorRequiresExplicitLastOrSessions() {
        AtomicInteger resumes = new AtomicInteger();
        InteractiveTaskCommandService service = service(new QueryStub(),
                countingPort(resumes), List.of(""));
        var result = service.resume(List.of(), session("ollama", "coder"));
        assertEquals("RESUME_SELECTOR_REQUIRED", result.code());
        assertEquals(0, resumes.get());
    }

    @Test void legacyResumeFailsClosedBeforeRuntime() {
        AtomicInteger resumes = new AtomicInteger();
        InteractiveTaskCommandService service = service(new QueryStub(),
                countingPort(resumes), List.of());
        var result = service.resume(List.of("task-1"), session("ollama", "other"));
        assertEquals("LEGACY_ID_REQUIRES_EXPLICIT_MIGRATION", result.code());
        assertEquals(0, resumes.get());
    }

    @Test void canonicalTaskIdIsNotInterpretedAsThread() {
        TaskSubmissionPort port = request ->
                TaskSubmissionResult.rejected("UNUSED", "unused");
        InteractiveTaskCommandService service = service(new QueryStub(), port, List.of());
        var result = service.resume(List.of(
                "tsk_123456789abc4def8000000000000001"),
                session("ollama", "coder"));
        assertEquals("WRONG_ENTITY_FAMILY", result.code());
    }

    @Test void rewindHashGuardRejectsBeforeConfirmationOrStoreMutation() {
        QueryStub query = new QueryStub(false);
        AtomicInteger prompts = new AtomicInteger();
        InteractiveTaskCommandService service = new InteractiveTaskCommandService(
                temp, query, request -> TaskSubmissionResult.accepted("unused"), prompt -> {
                    prompts.incrementAndGet(); return "y";
                });
        var result = service.rewind(List.of("snapshot-1"), sessionWithLastTask());
        assertEquals("REWIND_HASH_GUARD", result.code());
        assertEquals(0, prompts.get());
    }

    @Test void activeTaskRejectsResumeAndRewind() {
        InteractiveSession session = session("ollama", "coder");
        session.startTask("active-task");
        InteractiveTaskCommandService service = service(new QueryStub(),
                request -> TaskSubmissionResult.accepted("unused"), List.of());
        assertEquals("RESUME_TASK_ACTIVE", service.resume(List.of(), session).code());
        assertEquals("REWIND_TASK_ACTIVE", service.rewind(List.of(), session).code());
        assertEquals("active-task", session.snapshot().currentTaskId());
    }

    @Test void rewindPickerIsFriendlyByDefaultAndDetailsExposeSafetyEvidence() {
        QueryStub query = new QueryStub(true);
        java.util.ArrayList<String> defaultPrompts = new java.util.ArrayList<>();
        ArrayDeque<String> defaultAnswers = new ArrayDeque<>(List.of("1", ""));
        InteractiveTaskCommandService defaultService = new InteractiveTaskCommandService(
                temp, query, request -> TaskSubmissionResult.accepted("unused"), prompt -> {
                    defaultPrompts.add(prompt);
                    return defaultAnswers.removeFirst();
                });
        assertEquals("TASK_MANAGEMENT_CANCELLED",
                defaultService.rewind(List.of(), sessionWithLastTask()).code());
        String defaultOutput = String.join("\n", defaultPrompts);
        assertTrue(defaultOutput.contains("src/Main.java"));
        assertTrue(defaultOutput.contains("before edit"));
        assertTrue(defaultOutput.contains("safe=yes"));
        assertFalse(defaultOutput.contains("current-hash="));
        assertFalse(defaultOutput.contains("eligibility="));
        assertFalse(defaultOutput.contains("content hash"));

        java.util.ArrayList<String> detailsPrompts = new java.util.ArrayList<>();
        ArrayDeque<String> detailAnswers = new ArrayDeque<>(List.of("1", ""));
        InteractiveTaskCommandService detailService = new InteractiveTaskCommandService(
                temp, query, request -> TaskSubmissionResult.accepted("unused"), prompt -> {
                    detailsPrompts.add(prompt);
                    return detailAnswers.removeFirst();
                });
        assertEquals("TASK_MANAGEMENT_CANCELLED",
                detailService.rewind(List.of("--details"), sessionWithLastTask()).code());
        String detailsOutput = String.join("\n", detailsPrompts);
        assertTrue(detailsOutput.contains("current-hash=match"));
        assertTrue(detailsOutput.contains("eligibility=REWINDABLE"));
    }

    private InteractiveTaskCommandService service(InteractiveQueryFacade queries,
            TaskSubmissionPort port, List<String> answers) {
        ArrayDeque<String> input = new ArrayDeque<>(answers);
        return new InteractiveTaskCommandService(temp, queries, port,
                prompt -> input.isEmpty() ? null : input.removeFirst());
    }

    private static TaskSubmissionPort countingPort(AtomicInteger resumes) {
        return new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest request) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
        };
    }

    private InteractiveSession session(String provider, String model) {
        InteractiveSession session = new InteractiveSession("session-1", temp,
                provider, model, "on-request", InteractiveSession.TerminalMode.PLAIN,
                Instant.EPOCH);
        session.start(); return session;
    }

    private InteractiveSession sessionWithLastTask() {
        InteractiveSession session = session("ollama", "coder");
        session.startTask("task-1"); session.finishTask("task-1");
        return session;
    }

    private static class QueryStub implements InteractiveQueryFacade {
        private final boolean hashMatches;
        QueryStub() { this(true); }
        QueryStub(boolean hashMatches) { this.hashMatches = hashMatches; }
        public List<String> status(InteractiveSession.Snapshot session) { return List.of(); }
        public List<String> plan(InteractiveSession.Snapshot session) { return List.of(); }
        public List<String> tasks(int limit) { return List.of(); }
        public List<String> report(InteractiveSession.Snapshot session, String taskId) { return List.of(); }
        public List<InteractiveTaskView> taskViews(String filter, int limit) {
            return List.of(new InteractiveTaskView("task-1", "task-1", "2026-07-14",
                    "repair task", "ollama", "coder", "FAILED", "VERIFYING",
                    true, 1, "FAILED"));
        }
        public List<InteractiveSnapshotView> snapshotViews(String taskId, int limit) {
            return List.of(new InteractiveSnapshotView("snapshot-1", "snapshot-1", "task-1",
                    "2026-07-14", "src/Main.java", "before edit", hashMatches,
                    hashMatches, "APPLIED"));
        }
        public Optional<InteractiveSnapshotView> snapshotView(String snapshotId) {
            return snapshotViews("task-1", 1).stream()
                    .filter(item -> item.snapshotId().equals(snapshotId)).findFirst();
        }
    }
}
