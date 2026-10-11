package com.paicli.interactive.shell;

import com.paicli.interactive.command.InteractiveDoctorPort;
import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.interactive.command.SlashCommandRegistry;
import com.paicli.interactive.command.SlashCommandRouter;
import com.paicli.interactive.completion.InteractiveCompleter;
import com.paicli.interactive.completion.SlashCommandCompleter;
import com.paicli.interactive.completion.WorkspaceCompleter;
import com.paicli.interactive.completion.WorkspacePathGuard;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.input.InteractiveInputParser;
import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.render.InteractiveRuntimeEventRenderer;
import com.paicli.interactive.render.JLineShellRenderer;
import com.paicli.interactive.render.PlainRuntimeEventRenderer;
import com.paicli.interactive.render.PlainShellRenderer;
import com.paicli.interactive.render.RuntimeDisplayPreferences;
import com.paicli.interactive.render.TerminalOutputPort;
import com.paicli.interactive.runtime.InteractiveRuntimeAdapter;
import com.paicli.interactive.runtime.ProductionInteractiveTaskExecutor;
import com.paicli.interactive.session.InteractiveSession.TerminalMode;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.interactive.terminal.InteractiveLineReaderFactory;
import com.paicli.interactive.terminal.InteractiveTerminalFactory;
import com.paicli.interactive.provider.InteractiveProviderCommandPort;
import com.paicli.interactive.provider.InteractiveProviderCommandService;
import com.paicli.interactive.provider.InteractiveProviderState;
import com.paicli.interactive.task.InteractiveTaskCommandPort;
import com.paicli.interactive.task.InteractiveTaskCommandService;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import com.paicli.doctor.DoctorCommand;
import com.paicli.doctor.DoctorService;
import com.paicli.provider.ProviderHealthService;
import com.paicli.interactive.verification.InteractiveVerificationPreferencePort;
import org.jline.reader.LineReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.time.Clock;
import java.nio.file.Path;

/** Owns terminal and runtime composition for preview and production interactive shells. */
public final class InteractiveShellRunner {
    public InteractiveShellResult runJLinePreview(
            InteractiveShellConfig requested, InputStream input, OutputStream output,
            InteractiveHistoryStore history, InteractiveDoctorPort doctor) throws Exception {
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory();
        try (InteractiveTerminalFactory.TerminalContext terminal =
                     factory.create(input, output, false)) {
            WorkspacePathGuard guard = new WorkspacePathGuard(requested.workspace());
            SlashCommandRegistry registry = new SlashCommandRegistry();
            InteractiveCompleter completer = new InteractiveCompleter(
                    new SlashCommandCompleter(registry), new WorkspaceCompleter(guard));
            LineReader reader = new InteractiveLineReaderFactory()
                    .create(terminal.terminal(), completer, history);
            InteractiveShellConfig actual = actual(requested, terminal.mode());
            JLineShellInputReader shellInput = new JLineShellInputReader(reader);
            try (TerminalOutputPort terminalOutput =
                         TerminalOutputPort.jline(reader, shellInput::isReading)) {
                shellInput.bindOutput(terminalOutput);
                return create(actual, new InteractiveInputCoordinator(shellInput),
                        new JLineShellRenderer(terminalOutput), history, doctor, guard,
                        TaskSubmissionPort.runtimeNotConnected(),
                        InteractiveQueryFacade.unavailable(),
                        InteractiveProviderCommandPort.unavailable(),
                        InteractiveTaskCommandPort.unavailable()).run();
            }
        }
    }

    public InteractiveShellResult runPlainPreview(
            InteractiveShellConfig requested, Reader input, PrintWriter output,
            InteractiveHistoryStore history, InteractiveDoctorPort doctor) {
        WorkspacePathGuard guard = new WorkspacePathGuard(requested.workspace());
        InteractiveShellConfig actual = actual(requested, TerminalMode.PLAIN);
        PlainShellInputReader shellInput = new PlainShellInputReader(input);
        try (TerminalOutputPort terminalOutput =
                     TerminalOutputPort.plain(output, shellInput::isReading)) {
            shellInput.bindOutput(terminalOutput);
            return create(actual, new InteractiveInputCoordinator(shellInput),
                    new PlainShellRenderer(terminalOutput), history, doctor, guard,
                    TaskSubmissionPort.runtimeNotConnected(),
                    InteractiveQueryFacade.unavailable(),
                    InteractiveProviderCommandPort.unavailable(),
                    InteractiveTaskCommandPort.unavailable()).run();
        }
    }

    public InteractiveShellResult runSystemRuntime(
            InteractiveShellConfig requested, InteractiveHistoryStore history,
            InteractiveDoctorPort doctor) throws Exception {
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory();
        try (InteractiveTerminalFactory.TerminalContext terminal =
                     factory.createSystemOrPlain(System.in, System.out)) {
            if (terminal.plain()) {
                return runPlainRuntime(requested, terminal.terminal().reader(),
                        terminal.terminal().writer(), history, doctor);
            }
            WorkspacePathGuard guard = new WorkspacePathGuard(requested.workspace());
            SlashCommandRegistry registry = new SlashCommandRegistry();
            InteractiveCompleter completer = new InteractiveCompleter(
                    new SlashCommandCompleter(registry), new WorkspaceCompleter(guard));
            LineReader reader = new InteractiveLineReaderFactory()
                    .create(terminal.terminal(), completer, history);
            JLineShellInputReader shellInput = new JLineShellInputReader(reader);
            InteractiveInputCoordinator inputCoordinator = new InteractiveInputCoordinator(shellInput);
            ProviderRuntime providerRuntime = providerRuntime(requested.workspace(), inputCoordinator);
            RuntimeDisplayPreferences display = new RuntimeDisplayPreferences();
            try (TerminalOutputPort terminalOutput =
                         TerminalOutputPort.jline(reader, shellInput::isReading)) {
                shellInput.bindOutput(terminalOutput);
                InteractiveRuntimeEventRenderer events =
                        new InteractiveRuntimeEventRenderer(terminalOutput, display);
                try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                        requested.workspace(),
                        new ProductionInteractiveTaskExecutor(requested.workspace(),
                                providerRuntime.state()::config), events)) {
                    InteractiveShellConfig actual = actual(requested, terminal.mode(), providerRuntime.state());
                    InteractiveQueryFacade queries = new StateStoreInteractiveQueryFacade(
                            requested.workspace(), runtime.view());
                    return create(actual, inputCoordinator,
                            new JLineShellRenderer(terminalOutput), history, providerRuntime.doctor(), guard, runtime,
                            queries, providerRuntime.commands(),
                            new InteractiveTaskCommandService(requested.workspace(), queries,
                                    runtime, inputCoordinator.selectorPrompt()),
                            display, providerRuntime.verificationPreferences()).run();
                } finally {
                    events.flush();
                }
            }
        }
    }
    public InteractiveShellResult runJLineRuntime(
            InteractiveShellConfig requested, InputStream input, OutputStream output,
            InteractiveHistoryStore history, InteractiveDoctorPort doctor) throws Exception {
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory();
        try (InteractiveTerminalFactory.TerminalContext terminal =
                     factory.create(input, output, false)) {
            WorkspacePathGuard guard = new WorkspacePathGuard(requested.workspace());
            SlashCommandRegistry registry = new SlashCommandRegistry();
            InteractiveCompleter completer = new InteractiveCompleter(
                    new SlashCommandCompleter(registry), new WorkspaceCompleter(guard));
            LineReader reader = new InteractiveLineReaderFactory()
                    .create(terminal.terminal(), completer, history);
            JLineShellInputReader shellInput = new JLineShellInputReader(reader);
            InteractiveInputCoordinator inputCoordinator = new InteractiveInputCoordinator(shellInput);
            ProviderRuntime providerRuntime = providerRuntime(requested.workspace(), inputCoordinator);
            RuntimeDisplayPreferences display = new RuntimeDisplayPreferences();
            try (TerminalOutputPort terminalOutput =
                         TerminalOutputPort.jline(reader, shellInput::isReading)) {
                shellInput.bindOutput(terminalOutput);
                InteractiveRuntimeEventRenderer events =
                        new InteractiveRuntimeEventRenderer(terminalOutput, display);
                try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                        requested.workspace(),
                        new ProductionInteractiveTaskExecutor(requested.workspace(),
                                providerRuntime.state()::config), events)) {
                    InteractiveShellConfig actual = actual(requested, terminal.mode(), providerRuntime.state());
                    InteractiveQueryFacade queries = new StateStoreInteractiveQueryFacade(
                            requested.workspace(), runtime.view());
                    return create(actual, inputCoordinator,
                            new JLineShellRenderer(terminalOutput), history, providerRuntime.doctor(), guard, runtime,
                            queries, providerRuntime.commands(),
                            new InteractiveTaskCommandService(requested.workspace(), queries,
                                    runtime, inputCoordinator.selectorPrompt()),
                            display, providerRuntime.verificationPreferences()).run();
                } finally {
                    events.flush();
                }
            }
        }
    }

    public InteractiveShellResult runPlainRuntime(
            InteractiveShellConfig requested, Reader input, PrintWriter output,
            InteractiveHistoryStore history, InteractiveDoctorPort doctor) throws Exception {
        WorkspacePathGuard guard = new WorkspacePathGuard(requested.workspace());
        PlainShellInputReader shellInput = new PlainShellInputReader(input);
        InteractiveInputCoordinator inputCoordinator = new InteractiveInputCoordinator(shellInput);
        ProviderRuntime providerRuntime = providerRuntime(requested.workspace(), inputCoordinator);
        RuntimeDisplayPreferences display = new RuntimeDisplayPreferences();
        try (TerminalOutputPort terminalOutput =
                     TerminalOutputPort.plain(output, shellInput::isReading)) {
            shellInput.bindOutput(terminalOutput);
            PlainRuntimeEventRenderer events =
                    new PlainRuntimeEventRenderer(terminalOutput, display);
            try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                    requested.workspace(),
                    new ProductionInteractiveTaskExecutor(requested.workspace(),
                            providerRuntime.state()::config), events)) {
                InteractiveShellConfig actual = actual(requested, TerminalMode.PLAIN, providerRuntime.state());
                InteractiveQueryFacade queries = new StateStoreInteractiveQueryFacade(
                        requested.workspace(), runtime.view());
                return create(actual, inputCoordinator,
                        new PlainShellRenderer(terminalOutput), history, providerRuntime.doctor(), guard, runtime,
                        queries, providerRuntime.commands(),
                        new InteractiveTaskCommandService(requested.workspace(), queries,
                                runtime, inputCoordinator.selectorPrompt()),
                        display, providerRuntime.verificationPreferences()).run();
            } finally {
                events.flush();
            }
        }
    }

    private static InteractiveShellConfig actual(
            InteractiveShellConfig requested, TerminalMode mode) {
        return new InteractiveShellConfig(
                requested.applicationVersion(), requested.workspace(), requested.provider(),
                requested.model(), requested.approvalMode(), mode, requested.providerSource(),
                requested.verificationMode(), requested.verificationPreset(),
                requested.verificationSource(), requested.startupCommand(),
                requested.providerConfigurationFingerprint());
    }

    private static InteractiveShellConfig actual(InteractiveShellConfig requested,
            TerminalMode mode, InteractiveProviderState providerState) {
        com.paicli.config.Config config = providerState.config();
        return new InteractiveShellConfig(requested.applicationVersion(), requested.workspace(),
                config.effectiveProvider(), config.model(), requested.approvalMode(), mode,
                providerState.source().label(), requested.verificationMode(),
                requested.verificationPreset(), requested.verificationSource(),
                requested.startupCommand(),
                com.paicli.provider.ProviderHealthService.observationFingerprint(config));
    }

    private static InteractiveShell create(
            InteractiveShellConfig config, InteractiveInputCoordinator input,
            com.paicli.interactive.render.InteractiveShellRenderer renderer,
            InteractiveHistoryStore history, InteractiveDoctorPort doctor,
            WorkspacePathGuard guard, TaskSubmissionPort taskPort,
            InteractiveQueryFacade queries,
            InteractiveProviderCommandPort providers,
            InteractiveTaskCommandPort taskCommands) {
        return create(config, input, renderer, history, doctor, guard, taskPort,
                queries, providers, taskCommands, new RuntimeDisplayPreferences(),
                InteractiveVerificationPreferencePort.unavailable());
    }

    private static InteractiveShell create(
            InteractiveShellConfig config, InteractiveInputCoordinator input,
            com.paicli.interactive.render.InteractiveShellRenderer renderer,
            InteractiveHistoryStore history, InteractiveDoctorPort doctor,
            WorkspacePathGuard guard, TaskSubmissionPort taskPort,
            InteractiveQueryFacade queries,
            InteractiveProviderCommandPort providers,
            InteractiveTaskCommandPort taskCommands,
            RuntimeDisplayPreferences displayPreferences,
            InteractiveVerificationPreferencePort verificationPreferences) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        SlashCommandParser parser = new SlashCommandParser(registry);
        java.util.function.Supplier<com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot>
                compactionAuthority=taskPort instanceof InteractiveRuntimeAdapter runtime
                ?()->runtime.currentCompactionAuthority().orElse(null):()->null;
        java.util.function.BiConsumer<com.paicli.context.ContextModels.MaintenanceAuthority,
                com.paicli.context.CompactionCoordinator.Outcome> compactionEvents=
                taskPort instanceof InteractiveRuntimeAdapter runtime
                        ?runtime::contextCompacted:(authority,outcome)->{};
        java.util.function.BiFunction<java.util.List<String>, com.paicli.interactive.session.InteractiveSession,
                com.paicli.interactive.command.ShellCommandResult> mcpCommands = (args, session) -> {
            if (taskPort instanceof InteractiveRuntimeAdapter adapter) return adapter.mcpCommand(args, session);
            return com.paicli.interactive.command.ShellCommandResult.continueWith(
                    "MCP_UNAVAILABLE", java.util.List.of("MCP runtime unavailable."));
        };
        return new InteractiveShell(config, input, renderer,
                new InteractiveInputParser(guard),
                new SlashCommandRouter(
                        config.applicationVersion(), registry, parser, doctor, queries, providers,
                        taskCommands, displayPreferences, verificationPreferences,
                        new com.paicli.interactive.command.InteractiveCompactionCommandService(
                                config.workspace(),compactionAuthority,compactionEvents))
                        .withMcpCommands(mcpCommands).withSkillCommands((args,session)->
                                taskPort instanceof InteractiveRuntimeAdapter adapter?adapter.skillCommand(args,session):
                                com.paicli.interactive.command.ShellCommandResult.continueWith("SKILLS_UNAVAILABLE",java.util.List.of("Skills runtime unavailable."))),
                taskPort, history, new InteractivePromptRenderer(queries), Clock.systemUTC());
    }

    private static ProviderRuntime providerRuntime(Path workspace,
            InteractiveInputCoordinator input) {
        Path userHome = configuredUserHome();
        ConfigLoader loader = new ConfigLoader(workspace, userHome);
        InteractiveProviderState state = new InteractiveProviderState(loader.resolve());
        ProviderHealthService health = new ProviderHealthService();
        ProviderConfigStore store = new ProviderConfigStore(workspace, userHome);
        InteractiveProviderCommandService commands = new InteractiveProviderCommandService(
                state,loader,store,health,input.providerPrompt(),workspace);
        InteractiveDoctorPort doctor = () -> {
            java.io.StringWriter buffer = new java.io.StringWriter();
            new DoctorCommand(new DoctorService(workspace,
                    () -> new ConfigLoader.Resolution(state.config(), state.source()), health))
                    .execute(new PrintWriter(buffer, true));
            return buffer.toString().lines().limit(40).toList();
        };
        return new ProviderRuntime(state, commands, doctor,
                InteractiveVerificationPreferencePort.backedBy(store));
    }

    private static Path configuredUserHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.isBlank()) return null;
        try { return Path.of(value).toAbsolutePath().normalize(); }
        catch (RuntimeException error) { return null; }
    }

    private record ProviderRuntime(InteractiveProviderState state,
            InteractiveProviderCommandPort commands, InteractiveDoctorPort doctor,
            InteractiveVerificationPreferencePort verificationPreferences) { }
}
