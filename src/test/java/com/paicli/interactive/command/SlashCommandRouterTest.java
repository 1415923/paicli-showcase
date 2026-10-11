package com.paicli.interactive.command;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.render.RuntimeDisplayPreferences;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.InteractiveTaskCommandPort;
import com.paicli.interactive.verification.InteractiveVerificationPreferencePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandRouterTest {
    @TempDir Path temp;

    @Test void routesDoctorAndDefersRuntimeCommandsWithoutFakeData() {
        InteractiveSession session = session();
        SlashCommandRouter router = router();
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("history"));
        assertEquals(List.of("Overall: WARN"), router.route("/doctor", session, history).lines());
        ShellCommandResult deferred = router.route("/plan", session, history);
        assertEquals("PLAN", deferred.code());
        assertTrue(deferred.lines().getFirst().contains("unavailable"));
    }

    @Test void quitIsStrictExitAliasAndHistoryIsBoundedAndClearIsDurable() {
        InteractiveSession session = session();
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("command-history"));
        history.append("first");
        history.append("second");

        ShellCommandResult shown = router().route("/history 1", session, history);
        assertEquals("HISTORY", shown.code());
        assertEquals(List.of("1: second"), shown.lines());
        assertEquals(ShellCommandResult.Action.EXIT,
                router().route("/quit", session, history).action());

        ShellCommandResult cleared = router().route("/history clear", session, history);
        assertEquals("HISTORY_CLEARED", cleared.code());
        assertEquals(List.of(), history.load());
    }

    @Test void historyReadFailureIsNotReportedAsAnEmptyHistory() throws Exception {
        Path root = Files.createDirectories(temp.resolve("unreadable-history"));
        Files.createDirectories(root.resolve("interactive-history"));
        InteractiveHistoryStore history = new InteractiveHistoryStore(root);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> router().route("/history", session(), history));

        assertTrue(failure.getMessage().contains("persistent history unavailable"));
        assertTrue(failure.getMessage().contains("history read failed"));
        assertFalse(failure.getMessage().contains("History is empty"));
    }

    @Test void preservesActiveTaskGatesAndSafeStatus() {
        InteractiveSession session = session();
        session.startTask("task-1", "run-1");
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("history"));
        java.util.concurrent.atomic.AtomicInteger statusCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        com.paicli.interactive.query.InteractiveQueryFacade queries =
                new com.paicli.interactive.query.InteractiveQueryFacade() {
                    public List<String> status(InteractiveSession.Snapshot value) {
                        statusCalls.incrementAndGet();
                        return List.of("──────────────── compact status ────────────────");
                    }
                    public List<String> status(InteractiveSession.Snapshot value,
                            List<String> arguments) {
                        return status(value);
                    }
                    public List<String> plan(InteractiveSession.Snapshot value) { return List.of(); }
                    public List<String> tasks(int limit) { return List.of(); }
                    public List<String> report(InteractiveSession.Snapshot value,
                            String taskId) { return List.of(); }
                };
        SlashCommandRegistry registry = new SlashCommandRegistry();
        SlashCommandRouter router = new SlashCommandRouter("1.2.0", registry,
                new SlashCommandParser(registry), List::of, queries);
        assertThrows(IllegalStateException.class,
                () -> router.route("/clear", session, history));
        List<String> status = router.route("/status", session, history).lines();
        assertEquals(List.of("──────────────── compact status ────────────────"), status);
        assertEquals(1, statusCalls.get());
        assertFalse(String.join("\n", status).contains("Provider configured:"));
        assertFalse(String.join("\n", status).contains("Active task:"));
        assertFalse(String.join("\n", status).contains(temp.toAbsolutePath().toString()));
    }

    @Test void jointReportRetainsAuditSectionsAndStillBoundsUnexpectedLargeOutput() {
        var lines=new java.util.ArrayList<String>();
        for(int index=0;index<28;index++)lines.add("Task projection fact "+index);
        for(int index=0;index<6;index++)lines.add("MCP tool "+index+": SUCCEEDED; approval=APPROVED");
        lines.add("Skill browser-regression READ references/report.md status=SUCCEEDED");
        var queries=new com.paicli.interactive.query.InteractiveQueryFacade(){
            public List<String> status(InteractiveSession.Snapshot value){return List.of();}
            public List<String> plan(InteractiveSession.Snapshot value){return List.of();}
            public List<String> tasks(int limit){return List.of();}
            public List<String> report(InteractiveSession.Snapshot value,String selector){return List.copyOf(lines);}
        };
        var registry=new SlashCommandRegistry();
        var router=new SlashCommandRouter("test",registry,new SlashCommandParser(registry),List::of,queries);
        var report=router.route("/report task-1",session(),null);
        assertEquals(lines,report.lines(),"MCP and Skill evidence must survive the formal report router");
        lines.add("line\r\n"+"x".repeat(1000));
        for(int index=0;index<200;index++)lines.add("extra fact "+index);
        var bounded=router.route("/report task-1",session(),null).lines();
        assertTrue(bounded.size()<=97);assertEquals("[output truncated]",bounded.getLast());
        assertTrue(bounded.stream().allMatch(line->line.length()<=256&&!line.contains("\r")&&!line.contains("\n")));
    }

    @Test void resumeAndRewindRouteToProductionCommandPortWithoutPlaceholder() {
        InteractiveSession session = session();
        SlashCommandRegistry registry = new SlashCommandRegistry();
        InteractiveTaskCommandPort tasks = new InteractiveTaskCommandPort() {
            public ShellCommandResult resume(List<String> arguments, InteractiveSession value) {
                return ShellCommandResult.continueWith("RESUME_NONE", List.of("No recoverable task."));
            }
            public ShellCommandResult rewind(List<String> arguments, InteractiveSession value) {
                return ShellCommandResult.continueWith("REWIND_NONE", List.of("No snapshots."));
            }
            public ShellCommandResult continueTask(List<String> arguments,InteractiveSession value){
                return ShellCommandResult.continueWith("FOLLOW_UP",List.of(String.join(" ",arguments)));
            }
        };
        SlashCommandRouter router = new SlashCommandRouter("1.1.0", registry,
                new SlashCommandParser(registry), () -> List.of(),
                com.paicli.interactive.query.InteractiveQueryFacade.unavailable(),
                com.paicli.interactive.provider.InteractiveProviderCommandPort.unavailable(), tasks);
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("routing-history"));
        assertEquals("RESUME_NONE", router.route("/resume", session, history).code());
        assertEquals("REWIND_NONE", router.route("/rewind", session, history).code());
        assertEquals("FOLLOW_UP",router.route("/continue continue work",session,history).code());
    }

    @Test void verifyAutoUsesFrozenMavenPresetAndCannotChangeDuringTask() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>\n");
        InteractiveSession session = session();
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("verify-history"));
        ShellCommandResult enabled = router().route("/verify auto", session, history);
        assertTrue(enabled.lines().contains("Verification: auto (session)"));
        assertTrue(enabled.lines().stream().anyMatch(line -> line.contains("MAVEN_OFFLINE_TEST_V1")));
        assertEquals("auto", session.snapshot().verificationMode());
        session.startTask("task-1", "run-1");
        assertThrows(IllegalStateException.class,
                () -> router().route("/verify off", session, history));
        assertEquals("auto", session.snapshot().verificationMode());
    }

    @Test void verboseOnlyChangesSharedDisplayPreference() {
        InteractiveSession session = session();
        InteractiveHistoryStore history = new InteractiveHistoryStore(
                temp.resolve("verbose-history"));
        RuntimeDisplayPreferences display = new RuntimeDisplayPreferences();
        SlashCommandRouter router = router(display,
                InteractiveVerificationPreferencePort.unavailable());

        assertEquals(List.of("Verbose: off"),
                router.route("/verbose", session, history).lines());
        assertEquals(List.of("Verbose: on"),
                router.route("/verbose on", session, history).lines());
        assertTrue(display.verbose());
        assertEquals(List.of("Verbose: off"),
                router.route("/verbose off", session, history).lines());
        assertFalse(display.verbose());
        assertEquals("on-request", session.snapshot().approvalMode());
    }

    @Test void verifySavePersistsScopeAndActiveTaskCannotWritePreference()
            throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>\n");
        Path home = Files.createDirectories(temp.resolve("home"));
        ProviderConfigStore store = new ProviderConfigStore(temp, home);
        SlashCommandRouter router = router(new RuntimeDisplayPreferences(),
                InteractiveVerificationPreferencePort.backedBy(store));
        InteractiveHistoryStore history = new InteractiveHistoryStore(
                temp.resolve("saved-verify-history"));
        InteractiveSession session = session();

        ShellCommandResult saved = router.route(
                "/verify auto --save project", session, history);
        assertTrue(saved.lines().contains("Verification: auto (project)"));
        ConfigLoader.Resolution resolution = new ConfigLoader(temp, home).resolve();
        assertEquals(ConfigLoader.Source.PROJECT, resolution.verificationSource());
        assertEquals("mvn test", resolution.config().effectiveVerificationCommand());

        session.startTask("task-1", "run-1");
        assertThrows(IllegalStateException.class, () -> router.route(
                "/verify off --save user", session, history));
        assertFalse(Files.exists(home.resolve(".paicli/config.json")));
        assertEquals("auto", session.snapshot().verificationMode());
        assertEquals("project", session.snapshot().verificationSource());
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("session-1", temp,
                Config.PROVIDER_UNCONFIGURED, null, Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start(); return session;
    }

    private SlashCommandRouter router() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        return new SlashCommandRouter("1.1.0", registry, new SlashCommandParser(registry),
                () -> List.of("Overall: WARN"));
    }

    private SlashCommandRouter router(RuntimeDisplayPreferences display,
            InteractiveVerificationPreferencePort verification) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        return new SlashCommandRouter("1.1.0", registry,
                new SlashCommandParser(registry), () -> List.of("Overall: WARN"),
                com.paicli.interactive.query.InteractiveQueryFacade.unavailable(),
                com.paicli.interactive.provider.InteractiveProviderCommandPort.unavailable(),
                InteractiveTaskCommandPort.unavailable(), display, verification);
    }
}
