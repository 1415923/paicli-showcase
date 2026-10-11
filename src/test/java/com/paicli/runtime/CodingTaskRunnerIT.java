package com.paicli.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.config.ProjectInstructions;
import com.paicli.hook.HookDefinition;
import com.paicli.hook.HookEvent;
import com.paicli.hook.HookRunner;
import com.paicli.hook.HookRuntime;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.skill.SkillContextBuilder;
import com.paicli.skill.SkillLoader;
import com.paicli.skill.SkillRuntime;
import com.paicli.skill.SkillSelector;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ContextRebuilder;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.RepairAttemptStore;
import com.paicli.state.SkillSelectionStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskPlanStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTaskRunnerIT {

    @TempDir
    Path workspace;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void planExecuteAndPassedVerificationCompleteTaskAndRun() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("1. inspect\n2. implement\n3. test"),
                ModelResponse.finalAnswer("implementation complete"));
        Config config = config(passCommand(), 1);

        CodingTaskResult result = runner(provider, config, id -> new ToolRegistry())
                .run("runner-pass", "implement feature");

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        assertEquals(VerificationStore.STATUS_PASSED, result.verificationStatus());
        assertEquals(0, result.repairAttempts());
        assertEquals(CodingRunStore.PHASE_COMPLETED,
                stateStore.codingRuns.findById(result.runId()).orElseThrow().phase());
        assertEquals(TaskPlanStore.STATUS_COMPLETED,
                stateStore.plans.findById(result.planId()).orElseThrow().status());
        assertEquals(VerificationStore.STATUS_PASSED,
                stateStore.verifications.findByRun(result.runId()).getFirst().status());
        assertTrue(stateStore.repairAttempts.findByRun(result.runId()).isEmpty());
        assertFalse(stateStore.events.findMessages(result.taskId()).isEmpty());
        assertEquals("SEGMENT_COMPLETE",
                stateStore.checkpoints.loadLatest(result.taskId()).orElseThrow().status());
    }

    @Test
    void emptyVerificationCommandRecordsSkippedAndCompletes() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("done without verification"));

        CodingTaskResult result = runner(provider, config("", 1), id -> new ToolRegistry())
                .run("runner-skip", "task without verification command");

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        assertEquals(VerificationStore.STATUS_SKIPPED, result.verificationStatus());
        assertEquals(0, result.repairAttempts());
        assertTrue(stateStore.verifications.findByRun(result.runId()).isEmpty(),
                "no verification command is represented by planless UNVERIFIED evidence");
    }

    @Test
    void failedVerificationRepairsThenPassesAndIncludesFailureContext() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("approved plan text"),
                ModelResponse.finalAnswer("initial implementation"),
                ModelResponse.finalAnswer("repair complete"));
        String command = failOnceCommand("repair.marker");

        CodingTaskResult result = runner(provider, config(command, 1), id -> new ToolRegistry())
                .run("runner-repair-pass", "original repair goal");

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        assertEquals(VerificationStore.STATUS_PASSED, result.verificationStatus());
        assertEquals(1, result.repairAttempts());
        assertEquals(List.of(VerificationStore.STATUS_FAILED, VerificationStore.STATUS_PASSED),
                stateStore.verifications.findByRun(result.runId()).stream()
                        .map(VerificationStore.VerificationRecord::status).toList());
        assertEquals(RepairAttemptStore.STATUS_COMPLETED,
                stateStore.repairAttempts.findByRun(result.runId()).getFirst().status());
        assertEquals(1, stateStore.codingRuns.findById(result.runId())
                .orElseThrow().repairAttemptsCompleted());

        String repairPrompt = provider.messagesAt(2).getLast().content();
        assertTrue(repairPrompt.contains("original repair goal"));
        assertTrue(repairPrompt.contains("approved plan text"));
        assertTrue(repairPrompt.contains("repair.marker"));
        assertTrue(repairPrompt.contains("Exit code: 7"));
        assertTrue(repairPrompt.contains("Output excerpt:"));
        assertTrue(repairPrompt.contains("Error excerpt:"));
    }

    @Test
    void repeatedVerificationFailureStopsAtConfiguredRepairLimit() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("initial"),
                ModelResponse.finalAnswer("repair one"),
                ModelResponse.finalAnswer("repair two"));

        CodingTaskResult result = runner(provider, config(alwaysFailCommand(), 2),
                id -> new ToolRegistry()).run("runner-repair-limit", "fix persistent failure");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertEquals(VerificationStore.STATUS_FAILED, result.verificationStatus());
        assertEquals(2, result.repairAttempts());
        assertEquals(3, stateStore.verifications.findByRun(result.runId()).size());
        assertEquals(2, stateStore.repairAttempts.findByRun(result.runId()).size());
        assertEquals(CodingRunStore.PHASE_FAILED,
                stateStore.codingRuns.findById(result.runId()).orElseThrow().phase());
        assertEquals(TaskStore.STATUS_FAILED,
                stateStore.tasks.findById(result.taskId()).orElseThrow().status());
        assertEquals(TaskPlanStore.STATUS_FAILED,
                stateStore.plans.findById(result.planId()).orElseThrow().status());
        assertEquals(4, provider.callCount());
    }

    @Test
    void repairToolCallStillUsesApprovalPolicy() throws Exception {
        writeSkill("skills/commands/SKILL.md", "command-help", "Command repair help",
                "command", "run_command", "Suggest commands but never authorize them.");
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("initial"),
                ModelResponse.toolCallResponse("run verification helper",
                        new ToolCall("run_command", Map.of("command", "echo unsafe"))),
                ModelResponse.finalAnswer("repair response"));
        RecordingTool tool = new RecordingTool("run_command");

        Config config = skillsConfig(alwaysFailCommand(), 1, 3, 1024);
        CodingTaskResult result = runnerWithSkills(provider, config, taskId -> {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            return registry;
        }).run("runner-repair-approval", "repair with command");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertFalse(tool.executed);
        assertEquals("NEEDS_APPROVAL",
                stateStore.approvals.findByTask(result.taskId()).getFirst().decision());
        assertFalse(stateStore.toolCalls.findByTask(result.taskId()).getFirst().success());
    }

    @Test
    void repairWriteFileUsesSnapshotAndCanMakeVerificationPass() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("initial"),
                ModelResponse.toolCallResponse("write repair", new ToolCall("write_file", Map.of(
                        "path", "repaired.txt", "content", "fixed"))),
                ModelResponse.finalAnswer("repair written"));
        FileSnapshotService snapshotService = new FileSnapshotService(workspace, stateStore.snapshots);
        ControlPlanePaths protectedPaths = new ControlPlanePaths(
                workspace, List.of("skills", ".paicli/skills"),
                List.of(".paicli/hooks.json"));

        CodingTaskResult result = runner(provider, config(fileMustExistCommand("repaired.txt"), 1),
                taskId -> {
                    ToolRegistry registry = new ToolRegistry();
                    registry.register(new WriteFileTool(
                            new SafetyPolicy(workspace), snapshotService, taskId,
                            null, protectedPaths));
                    return registry;
                }).run("runner-repair-snapshot", "create repaired file");

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        assertEquals("fixed", Files.readString(workspace.resolve("repaired.txt")));
        FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots
                .findByTask(result.taskId()).getFirst();
        assertEquals(FileSnapshotStore.STATUS_APPLIED, snapshot.status());
        assertEquals(FileSnapshotStore.OPERATION_CREATE, snapshot.operation());
        assertEquals("ALLOW", stateStore.approvals
                .findByTask(result.taskId()).getFirst().decision());
    }

    @Test
    void explicitSkillsAreInjectedIntoPlanExecuteAndRepair() throws Exception {
        writeSkill("skills/java/SKILL.md", "java-build", "Java Maven build support",
                "java", "run_command",
                "Use Maven safely. Authorization: Bearer skill-secret\n" + "x".repeat(2000));
        writeSkill("skills/second/SKILL.md", "second-java", "Other Java support",
                "java", "write_file", "second body");
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("approved plan"),
                ModelResponse.finalAnswer("initial implementation"),
                ModelResponse.finalAnswer("repair complete"));
        Config config = skillsConfig(failOnceCommand("skills-repair.marker"), 1, 1, 1024);

        CodingTaskResult result = runnerWithSkills(provider, config, id -> new ToolRegistry())
                .run("runner-skills", "use $java-build to implement java feature");

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        assertEquals(3, stateStore.skillSelections
                .findSelectionsByRun(result.runId()).size());
        assertEquals(List.of(
                        SkillSelectionStore.STAGE_PLAN,
                        SkillSelectionStore.STAGE_EXECUTE,
                        SkillSelectionStore.STAGE_REPAIR),
                stateStore.skillSelections.findSelectionsByRun(result.runId()).stream()
                        .map(SkillSelectionStore.SkillSelectionRecord::stage).toList());
        assertEquals(3, stateStore.skillSelections
                .findSelectedByRun(result.runId()).size());

        for (int call = 0; call < 3; call++) {
            String prompt = provider.messagesAt(call).getLast().content();
            assertTrue(prompt.contains("<untrusted_skill_context>"));
            assertTrue(prompt.contains("hint only (not authorization)"));
            assertFalse(prompt.contains("skill-secret"));
            int start = prompt.indexOf("<untrusted_skill_context>");
            int end = prompt.indexOf("</untrusted_skill_context>")
                    + "</untrusted_skill_context>".length();
            assertTrue(end - start <= 1024);
        }
        assertTrue(provider.messagesAt(2).getLast().content().contains("repair-needed"));
    }

    @Test
    void zeroSkillMatchesAreAuditedWithoutChangingSkippedCompletion() throws Exception {
        writeSkill("skills/docs/SKILL.md", "docs-only", "Documentation prose",
                "documentation", "", "Review prose only.");
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("done"));
        Config config = skillsConfig("", 1, 3, 1024);

        CodingTaskResult result = runnerWithSkills(provider, config, id -> new ToolRegistry())
                .run("runner-zero-skills", "compile java bytecode");

        assertEquals(VerificationStore.STATUS_SKIPPED, result.verificationStatus());
        assertEquals(2, stateStore.skillSelections
                .findSelectionsByRun(result.runId()).size());
        assertTrue(stateStore.skillSelections.findSelectionsByRun(result.runId()).stream()
                .allMatch(selection -> selection.selectedCount() == 0));
        assertTrue(stateStore.skillSelections.findSelectedByRun(result.runId()).isEmpty());
    }

    @Test
    void hooksRunAcrossPlanExecuteVerifyRepairAndFinishWithoutEnteringPrompts()
            throws Exception {
        String hookOutput = "HOOK_OUTPUT_MUST_NOT_ENTER_MODEL";
        List<HookDefinition> hooks = java.util.Arrays.stream(HookEvent.values())
                .map(event -> hook(event.name().toLowerCase(Locale.ROOT), event,
                        "echo " + hookOutput, "warn", 5))
                .toList();
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("initial"),
                ModelResponse.finalAnswer("repair"));
        Config config = config(failOnceCommand("hook-lifecycle.marker"), 1);

        CodingTaskResult result = runnerWithHooks(
                provider, config, id -> new ToolRegistry(), hooks)
                .run("runner-hooks-all", "repair lifecycle task");

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        List<com.paicli.state.HookRunStore.HookRunRecord> runs =
                stateStore.hookRuns.findByRun(result.runId());
        assertEquals(11, runs.size());
        for (HookEvent event : HookEvent.values()) {
            long expected = event == HookEvent.BEFORE_VERIFY
                    || event == HookEvent.AFTER_VERIFY ? 2 : 1;
            assertEquals(expected, runs.stream()
                    .filter(run -> event.name().equals(run.event())).count(), event.name());
        }
        assertEquals(1, runs.stream()
                .filter(run -> HookEvent.BEFORE_FINISH.name().equals(run.event())).count());
        for (int call = 0; call < provider.callCount(); call++) {
            assertTrue(provider.messagesAt(call).stream()
                    .noneMatch(message -> message.content().contains(hookOutput)));
        }
    }

    @Test
    void warnPolicyDoesNotBlockButBeforeFailPreventsPlanning() throws Exception {
        SequenceProvider warnProvider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"), ModelResponse.finalAnswer("done"));
        CodingTaskResult warned = runnerWithHooks(
                warnProvider, config("", 1), id -> new ToolRegistry(),
                List.of(
                        hook("warn-plan", HookEvent.BEFORE_PLAN,
                                alwaysFailCommand(), "warn", 5),
                        hook("timeout-warn", HookEvent.BEFORE_PLAN,
                                slowCommand(), "warn", 1)))
                .run("runner-hook-warn", "warn task");
        assertEquals(TaskStore.STATUS_DONE, warned.finalStatus());
        assertEquals(com.paicli.state.HookRunStore.STATUS_WARNED,
                stateStore.hookRuns.findByRun(warned.runId()).getFirst().status());
        assertEquals(com.paicli.state.HookRunStore.STATUS_TIMED_OUT,
                stateStore.hookRuns.findByRun(warned.runId()).get(1).status());

        SequenceProvider failProvider = new SequenceProvider(
                ModelResponse.finalAnswer("must not be requested"));
        CodingTaskResult failed = runnerWithHooks(
                failProvider, config("", 1), id -> new ToolRegistry(),
                List.of(hook("fail-plan", HookEvent.BEFORE_PLAN,
                        "git commit -m forbidden", "fail", 5)))
                .run("runner-hook-before-fail", "blocked planning");
        assertEquals(TaskStore.STATUS_FAILED, failed.finalStatus());
        assertEquals(0, failProvider.callCount());
        assertTrue(stateStore.plans.findByRun(failed.runId()).isEmpty());
        assertEquals(com.paicli.state.HookRunStore.STATUS_BLOCKED,
                stateStore.hookRuns.findByRun(failed.runId()).getFirst().status());
    }

    @Test
    void afterFailureMarksTaskFailedAfterStageAction() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"), ModelResponse.finalAnswer("executed"));
        CodingTaskResult result = runnerWithHooks(
                provider, config(passCommand(), 1), id -> new ToolRegistry(),
                List.of(hook("after-execute", HookEvent.AFTER_EXECUTE,
                        alwaysFailCommand(), "fail", 5)))
                .run("runner-hook-after-fail", "execute then fail");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertEquals(2, provider.callCount());
        assertTrue(stateStore.verifications.findByRun(result.runId()).isEmpty());
        assertTrue(stateStore.codingRuns.findById(result.runId()).orElseThrow()
                .lastError().contains("AFTER_EXECUTE"));
    }

    @Test
    void beforeFinishFailureAlwaysPersistsTerminalFailure() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"), ModelResponse.finalAnswer("done"));
        CodingTaskResult result = runnerWithHooks(
                provider, config("", 1), id -> new ToolRegistry(),
                List.of(hook("finish-fail", HookEvent.BEFORE_FINISH,
                        alwaysFailCommand(), "fail", 5)))
                .run("runner-hook-finish-fail", "finish failure");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertEquals(CodingRunStore.PHASE_FAILED,
                stateStore.codingRuns.findById(result.runId()).orElseThrow().phase());
        assertTrue(stateStore.codingRuns.findActiveByTask(result.taskId()).isEmpty());
        assertEquals(1, stateStore.hookRuns.findByRun(result.runId()).size());
    }

    @Test
    void blockedWarnContinuesAndTimeoutFailStopsBeforeExecute() throws Exception {
        SequenceProvider blockedProvider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"), ModelResponse.finalAnswer("done"));
        CodingTaskResult blocked = runnerWithHooks(
                blockedProvider, config("", 1), id -> new ToolRegistry(),
                List.of(hook("blocked-warn", HookEvent.BEFORE_PLAN,
                        "git commit -m forbidden", "warn", 5)))
                .run("runner-hook-blocked-warn", "blocked warn");
        assertEquals(TaskStore.STATUS_DONE, blocked.finalStatus());
        assertEquals(com.paicli.state.HookRunStore.STATUS_BLOCKED,
                stateStore.hookRuns.findByRun(blocked.runId()).getFirst().status());

        SequenceProvider timeoutProvider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("must not execute"));
        CodingTaskResult timedOut = runnerWithHooks(
                timeoutProvider, config("", 1), id -> new ToolRegistry(),
                List.of(hook("timeout-fail", HookEvent.BEFORE_EXECUTE,
                        slowCommand(), "fail", 1)))
                .run("runner-hook-timeout", "timeout task");
        assertEquals(TaskStore.STATUS_FAILED, timedOut.finalStatus());
        assertEquals(1, timeoutProvider.callCount());
        assertEquals(com.paicli.state.HookRunStore.STATUS_TIMED_OUT,
                stateStore.hookRuns.findByRun(timedOut.runId()).getFirst().status());
    }

    @Test
    void activeResumeReusesCompletedHookOccurrence() throws Exception {
        stateStore.tasks.createTask(
                "resume-hooks", "resume goal", "mock", null, "on-request", 3);
        stateStore.codingRuns.create(
                "resume-run", "resume-hooks", CodingRunStore.KIND_RESUME, "", 1, 0);
        List<HookDefinition> hooks = List.of(hook(
                "resume-before-plan", HookEvent.BEFORE_PLAN,
                "echo resume", "warn", 5));
        HookRuntime runtime = hookRuntime(hooks);
        HookRuntime.LifecycleResult original = runtime.run(
                "resume-hooks", "resume-run", HookEvent.BEFORE_PLAN, "plan");
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("resume plan"), ModelResponse.finalAnswer("done"));
        CodingTaskRunner runner = runnerWithHookRuntime(
                provider, config("", 1), id -> new ToolRegistry(), runtime);
        ContextRebuilder.ResumedContext context = new ContextRebuilder.ResumedContext(
                "resume-hooks", "resume goal", List.of(), 0, "",
                new ProjectInstructions("", List.of(), 0, 0));

        CodingTaskResult result = runner.resume(context);

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        List<com.paicli.state.HookRunStore.HookRunRecord> records =
                stateStore.hookRuns.findByRun("resume-run");
        assertEquals(1, records.size());
        assertEquals(original.results().getFirst().hookRunId(), records.getFirst().hookRunId());
    }

    @Test
    void resumeToolCallStillFailsClosedForNeedsApproval() throws Exception {
        stateStore.tasks.createTask(
                "resume-approval-needs", "resume high-risk command",
                "mock", null, Config.MODE_ON_REQUEST, 3);
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("resume plan"),
                ModelResponse.toolCallResponse("resume command", new ToolCall(
                        "run_command", Map.of("command", "echo unsafe"))),
                ModelResponse.finalAnswer("resume observed block"));
        RecordingTool tool = new RecordingTool("run_command");
        CodingTaskRunner runner = runner(provider, config("", 1), taskId -> {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            return registry;
        });
        ContextRebuilder.ResumedContext context = new ContextRebuilder.ResumedContext(
                "resume-approval-needs", "resume high-risk command",
                List.of(AgentMessage.userGoal("prior conversation")), 0, "",
                new ProjectInstructions("", List.of(), 0, 0));

        CodingTaskResult result = runner.resume(context);

        assertEquals(TaskStore.STATUS_DONE, result.finalStatus());
        assertFalse(tool.executed);
        assertEquals("NEEDS_APPROVAL",
                stateStore.approvals.findByTask(result.taskId()).getFirst().decision());
        assertFalse(stateStore.toolCalls.findByTask(result.taskId()).getFirst().success());
        assertTrue(stateStore.toolCalls.findByTask(result.taskId()).getFirst()
                .error().contains("APPROVAL_REQUIRED"));
    }

    @Test
    void repairToolCallStillFailsClosedForDenyMode() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("plan"),
                ModelResponse.finalAnswer("initial"),
                ModelResponse.toolCallResponse("repair command", new ToolCall(
                        "run_command", Map.of("command", "echo unsafe"))),
                ModelResponse.finalAnswer("repair observed deny"));
        RecordingTool tool = new RecordingTool("run_command");
        Config config = config(alwaysFailCommand(), 1, Config.MODE_DENY);

        CodingTaskResult result = runner(provider, config, taskId -> {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            return registry;
        }).run("runner-repair-deny", "repair should not execute denied command");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertFalse(tool.executed);
        assertEquals("DENY",
                stateStore.approvals.findByTask(result.taskId()).getFirst().decision());
        assertFalse(stateStore.toolCalls.findByTask(result.taskId()).getFirst().success());
        assertTrue(stateStore.toolCalls.findByTask(result.taskId()).getFirst()
                .error().contains("DENIED"));
    }
    @Test
    void planFailureMarksTaskAndRunFailedWithoutVerification() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("[MODEL_ERROR] unavailable"));

        CodingTaskResult result = runner(provider, config(passCommand(), 1),
                id -> new ToolRegistry()).run("runner-plan-fail", "plan should fail");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertNull(result.planId());
        assertNull(result.verificationStatus());
        assertEquals(CodingRunStore.PHASE_FAILED,
                stateStore.codingRuns.findById(result.runId()).orElseThrow().phase());
        assertEquals(TaskStore.STATUS_FAILED,
                stateStore.tasks.findById(result.taskId()).orElseThrow().status());
        assertTrue(stateStore.plans.findByRun(result.runId()).isEmpty());
        assertTrue(stateStore.verifications.findByRun(result.runId()).isEmpty());
    }

    @Test
    void initialAgentSegmentFailureMarksPlanRunAndTaskFailed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return ModelResponse.finalAnswer("plan");
            }
            throw new IllegalStateException("segment provider failed");
        };

        CodingTaskResult result = runner(provider, config(passCommand(), 1),
                id -> new ToolRegistry()).run("runner-segment-fail", "segment should fail");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertEquals(CodingRunStore.PHASE_FAILED,
                stateStore.codingRuns.findById(result.runId()).orElseThrow().phase());
        assertEquals(TaskPlanStore.STATUS_FAILED,
                stateStore.plans.findById(result.planId()).orElseThrow().status());
        assertEquals("SEGMENT_FAILED",
                stateStore.checkpoints.loadLatest(result.taskId()).orElseThrow().status());
        assertTrue(stateStore.verifications.findByRun(result.runId()).isEmpty());
    }

    @Test
    void providerSQLExceptionIsTrackedAsAgentFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return ModelResponse.finalAnswer("plan");
            }
            throw new IllegalStateException("provider failed", new SQLException("provider db"));
        };

        CodingTaskResult result = runner(provider, config(passCommand(), 1),
                id -> new ToolRegistry()).run("runner-provider-sql", "track provider failure");

        assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
        assertEquals(CodingRunStore.PHASE_FAILED,
                stateStore.codingRuns.findById(result.runId()).orElseThrow().phase());
        assertTrue(stateStore.verifications.findByRun(result.runId()).isEmpty());
    }

    @Test
    void sqlitePersistenceFailureStopsBeforeVerification() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider = (messages, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return ModelResponse.finalAnswer("plan");
            }
            try {
                stateStore.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return ModelResponse.finalAnswer("cannot be persisted");
        };
        Config config = config("echo verification-ran > verification-ran.txt", 1);
        CodingTaskRunner runner = runner(provider, config, id -> new ToolRegistry());

        assertThrows(SQLException.class,
                () -> runner.run("runner-persistence-fail", "stop on sqlite failure"));

        assertEquals(2, calls.get());
        assertFalse(Files.exists(workspace.resolve("verification-ran.txt")));
        stateStore = null;
    }

    private CodingTaskRunner runner(ModelProvider provider, Config config,
                                    Function<String, ToolRegistry> registryFactory) throws Exception {
        PlanService planService = new PlanService(provider, stateStore.plans);
        VerificationService verificationService = new VerificationService(
                workspace, stateStore.verifications, new PersistenceSanitizer(),
                config.effectiveVerificationTimeoutSeconds(),
                config.effectiveMaxVerificationOutputBytes());
        CodingTaskRunner.AgentLoopFactory loopFactory = taskId -> new AgentLoop(
                provider,
                registryFactory.apply(taskId),
                config.effectiveMaxIterations(),
                new ApprovalPolicy(config, taskId),
                null,
                stateStore,
                taskId,
                config.effectiveProvider(),
                config.model());
        return new CodingTaskRunner(
                stateStore, planService, verificationService, loopFactory, config);
    }

    private CodingTaskRunner runnerWithSkills(
            ModelProvider provider, Config config,
            Function<String, ToolRegistry> registryFactory) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        PlanService planService = new PlanService(provider, stateStore.plans);
        VerificationService verificationService = new VerificationService(
                workspace, stateStore.verifications, sanitizer,
                config.effectiveVerificationTimeoutSeconds(),
                config.effectiveMaxVerificationOutputBytes());
        CodingTaskRunner.AgentLoopFactory loopFactory = taskId -> new AgentLoop(
                provider, registryFactory.apply(taskId), config.effectiveMaxIterations(),
                new ApprovalPolicy(config, taskId), null, stateStore, taskId,
                config.effectiveProvider(), config.model());
        SkillRuntime skills = new SkillRuntime(
                new SkillLoader(workspace, stateStore.skillLoads, sanitizer),
                new SkillSelector(stateStore.skillSelections, sanitizer),
                new SkillContextBuilder(sanitizer), stateStore.skillSelections, config);
        return new CodingTaskRunner(stateStore, planService, verificationService,
                loopFactory, config, sanitizer, skills);
    }

    private CodingTaskRunner runnerWithHooks(
            ModelProvider provider, Config config,
            Function<String, ToolRegistry> registryFactory,
            List<HookDefinition> hooks) throws Exception {
        return runnerWithHookRuntime(
                provider, config, registryFactory, hookRuntime(hooks));
    }

    private CodingTaskRunner runnerWithHookRuntime(
            ModelProvider provider, Config config,
            Function<String, ToolRegistry> registryFactory,
            HookRuntime hooks) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        PlanService planService = new PlanService(provider, stateStore.plans);
        VerificationService verificationService = new VerificationService(
                workspace, stateStore.verifications, sanitizer,
                config.effectiveVerificationTimeoutSeconds(),
                config.effectiveMaxVerificationOutputBytes());
        CodingTaskRunner.AgentLoopFactory loopFactory = taskId -> new AgentLoop(
                provider, registryFactory.apply(taskId), config.effectiveMaxIterations(),
                new ApprovalPolicy(config, taskId), null, stateStore, taskId,
                config.effectiveProvider(), config.model());
        return new CodingTaskRunner(
                stateStore, planService, verificationService, loopFactory,
                config, sanitizer, null, hooks);
    }

    private HookRuntime hookRuntime(List<HookDefinition> hooks) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        HookRunner runner = new HookRunner(
                stateStore.hookRuns,
                new BoundedProcessRunner(workspace, sanitizer, 4096),
                sanitizer, 4096);
        return new HookRuntime(hooks, runner, stateStore.hookRuns);
    }

    private HookDefinition hook(String id, HookEvent event, String command,
                                String policy, int timeoutSeconds) {
        return new HookDefinition(
                id, event, command, timeoutSeconds, policy, true);
    }

    private Config config(String verificationCommand, int maxRepairAttempts) {
        return config(verificationCommand, maxRepairAttempts, Config.MODE_ON_REQUEST);
    }

    private Config config(String verificationCommand, int maxRepairAttempts,
                          String approvalMode) {
        return new Config(
                workspace.toString(), 30, 50_000, 200_000,
                approvalMode, 10_000,
                Config.PROVIDER_MOCK, null, null, null, null, 3,
                verificationCommand, 5, 4096, maxRepairAttempts,
                true, true, true);
    }

    private Config skillsConfig(String verificationCommand, int maxRepairAttempts,
                                int maxSelectedSkills, int maxContextChars) {
        return new Config(
                workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_ON_REQUEST, 10_000,
                Config.PROVIDER_MOCK, null, null, null, null, 3,
                verificationCommand, 5, 4096, maxRepairAttempts,
                true, true, true,
                true, List.of("skills", ".paicli/skills"),
                maxContextChars, maxSelectedSkills);
    }

    private void writeSkill(String relativePath, String name, String description,
                            String trigger, String allowedTool, String body) throws Exception {
        Path path = workspace.resolve(relativePath);
        Files.createDirectories(path.getParent());
        String allowed = allowedTool.isBlank() ? ""
                : "allowed_tools: [" + allowedTool + "]\n";
        Files.writeString(path, "---\nname: " + name + "\ndescription: " + description
                + "\ntriggers: [" + trigger + "]\n" + allowed + "---\n" + body);
    }

    private String passCommand() {
        return "echo verification-passed";
    }

    private String alwaysFailCommand() {
        return isWindows()
                ? "echo verification-failed 1>&2 & exit /b 7"
                : "echo verification-failed >&2; exit 7";
    }

    private String failOnceCommand(String marker) {
        return isWindows()
                ? "if exist " + marker
                + " (exit /b 0) else (echo repair-needed 1>&2 & type nul > "
                + marker + " & exit /b 7)"
                : "if [ -f " + marker
                + " ]; then exit 0; else echo repair-needed >&2; touch "
                + marker + "; exit 7; fi";
    }

    private String fileMustExistCommand(String file) {
        return isWindows()
                ? "if exist " + file + " (exit /b 0) else (echo missing 1>&2 & exit /b 1)"
                : "if [ -f " + file + " ]; then exit 0; else echo missing >&2; exit 1; fi";
    }

    private String slowCommand() {
        return isWindows() ? "ping -n 6 127.0.0.1 >NUL" : "sleep 5";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static final class SequenceProvider implements ModelProvider {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        private final List<List<AgentMessage>> calls = new ArrayList<>();

        private SequenceProvider(ModelResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages,
                                  List<ToolDefinition> toolDefinitions) {
            calls.add(List.copyOf(messages));
            ModelResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("No configured model response");
            }
            return response;
        }

        private List<AgentMessage> messagesAt(int index) {
            return calls.get(index);
        }

        private int callCount() {
            return calls.size();
        }
    }

    private static final class RecordingTool implements Tool {
        private final String name;
        private boolean executed;

        private RecordingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Runner test tool";
        }

        @Override
        public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description(), JsonNodeFactory.instance.objectNode());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executed = true;
            return ToolResult.success("executed");
        }
    }
}
