package com.paicli.interactive.command;

import com.paicli.config.Config;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.provider.InteractiveProviderCommandPort;
import com.paicli.interactive.task.InteractiveTaskCommandPort;
import com.paicli.interactive.render.RuntimeDisplayPreferences;
import com.paicli.interactive.verification.InteractiveVerificationPreferencePort;

import java.util.ArrayList;
import java.util.List;

public final class SlashCommandRouter {
    private final String applicationVersion;
    private final SlashCommandRegistry registry;
    private final SlashCommandParser parser;
    private final InteractiveDoctorPort doctor;
    private final InteractiveQueryFacade queries;
    private final InteractiveProviderCommandPort providers;
    private final InteractiveTaskCommandPort taskCommands;
    private final RuntimeDisplayPreferences displayPreferences;
    private final InteractiveVerificationPreferencePort verificationPreferences;
    private final InteractiveCompactionCommandPort compactions;
    private java.util.function.BiFunction<List<String>,InteractiveSession,ShellCommandResult> mcpCommands =
            (args, session) -> ShellCommandResult.continueWith("MCP_UNAVAILABLE", List.of("MCP runtime unavailable."));
    private java.util.function.BiFunction<List<String>,InteractiveSession,ShellCommandResult> skillCommands =
            (args, session) -> ShellCommandResult.continueWith("SKILLS_UNAVAILABLE", List.of("Skills runtime unavailable."));

    public SlashCommandRouter withSkillCommands(
            java.util.function.BiFunction<List<String>,InteractiveSession,ShellCommandResult> commands) {
        skillCommands=java.util.Objects.requireNonNull(commands);return this;
    }

    public SlashCommandRouter withMcpCommands(
            java.util.function.BiFunction<List<String>,InteractiveSession,ShellCommandResult> commands) {
        mcpCommands = java.util.Objects.requireNonNull(commands);
        return this;
    }

    public SlashCommandParser.ParsedSlashCommand parse(String input,boolean taskRunning){
        return parser.parse(input,taskRunning);
    }

    public SlashCommandRouter(String applicationVersion, SlashCommandRegistry registry,
            SlashCommandParser parser, InteractiveDoctorPort doctor) {
        this(applicationVersion, registry, parser, doctor,
                InteractiveQueryFacade.unavailable(),
                InteractiveProviderCommandPort.unavailable(),
                InteractiveTaskCommandPort.unavailable(),
                new RuntimeDisplayPreferences(),
                InteractiveVerificationPreferencePort.unavailable());
    }

    public SlashCommandRouter(String applicationVersion, SlashCommandRegistry registry,
            SlashCommandParser parser, InteractiveDoctorPort doctor,
            InteractiveQueryFacade queries) {
        this(applicationVersion, registry, parser, doctor, queries,
                InteractiveProviderCommandPort.unavailable(),
                InteractiveTaskCommandPort.unavailable(),
                new RuntimeDisplayPreferences(),
                InteractiveVerificationPreferencePort.unavailable());
    }

    public SlashCommandRouter(String applicationVersion, SlashCommandRegistry registry,
            SlashCommandParser parser, InteractiveDoctorPort doctor,
            InteractiveQueryFacade queries, InteractiveProviderCommandPort providers) {
        this(applicationVersion, registry, parser, doctor, queries, providers,
                InteractiveTaskCommandPort.unavailable(),
                new RuntimeDisplayPreferences(),
                InteractiveVerificationPreferencePort.unavailable());
    }

    public SlashCommandRouter(String applicationVersion, SlashCommandRegistry registry,
            SlashCommandParser parser, InteractiveDoctorPort doctor,
            InteractiveQueryFacade queries, InteractiveProviderCommandPort providers,
            InteractiveTaskCommandPort taskCommands) {
        this(applicationVersion, registry, parser, doctor, queries, providers,
                taskCommands, new RuntimeDisplayPreferences(),
                InteractiveVerificationPreferencePort.unavailable(),
                InteractiveCompactionCommandPort.unavailable());
    }

    public SlashCommandRouter(String applicationVersion, SlashCommandRegistry registry,
            SlashCommandParser parser, InteractiveDoctorPort doctor,
            InteractiveQueryFacade queries, InteractiveProviderCommandPort providers,
            InteractiveTaskCommandPort taskCommands,
            RuntimeDisplayPreferences displayPreferences,
            InteractiveVerificationPreferencePort verificationPreferences) {
        this(applicationVersion,registry,parser,doctor,queries,providers,taskCommands,
                displayPreferences,verificationPreferences,InteractiveCompactionCommandPort.unavailable());
    }

    public SlashCommandRouter(String applicationVersion, SlashCommandRegistry registry,
            SlashCommandParser parser, InteractiveDoctorPort doctor,
            InteractiveQueryFacade queries, InteractiveProviderCommandPort providers,
            InteractiveTaskCommandPort taskCommands,
            RuntimeDisplayPreferences displayPreferences,
            InteractiveVerificationPreferencePort verificationPreferences,
            InteractiveCompactionCommandPort compactions) {
        this.applicationVersion = applicationVersion;
        this.registry = registry;
        this.parser = parser;
        this.doctor = doctor;
        this.queries = queries;
        this.providers = providers;
        this.taskCommands = taskCommands;
        this.displayPreferences = displayPreferences;
        this.verificationPreferences = verificationPreferences;
        this.compactions=compactions;
    }

    public ShellCommandResult route(String input, InteractiveSession session,
            InteractiveHistoryStore history) {
        InteractiveSession.Snapshot before = session.snapshot();
        SlashCommandParser.ParsedSlashCommand parsed = parser.parse(input, before.currentTaskId() != null);
        return switch (parsed.command()) {
            case HELP -> ShellCommandResult.continueWith("HELP", help());
            case STATUS -> ShellCommandResult.continueWith("STATUS",
                    bounded(queries.status(before, parsed.arguments())));
            case MODE -> {
                if (parsed.arguments().isEmpty()) {
                    yield ShellCommandResult.continueWith("MODE", List.of(
                            "Current mode: " + before.approvalMode(),
                            "Modes: plan, read-only, on-request, workspace-write"));
                }
                String selected = session.changeApprovalMode(parsed.arguments().getFirst());
                yield ShellCommandResult.continueWith("MODE",
                        List.of("Approval mode: " + selected));
            }
            case PROVIDER -> providers.provider(parsed.arguments(), session);
            case MODEL -> providers.model(parsed.arguments(), session);
            case MCP -> mcpCommands.apply(parsed.arguments(), session);
            case SKILLS -> skillCommands.apply(parsed.arguments(),session);
            case DIFF -> ShellCommandResult.continueWith(
                    "DIFF", bounded(queries.diff(before)));
            case PLAN -> ShellCommandResult.continueWith(
                    "PLAN", queries.plan(before, !parsed.arguments().isEmpty()));
            case TASKS -> {
                yield ShellCommandResult.continueWith(
                        "TASKS", bounded(queries.tasks(before,parsed.arguments())));
            }
            case SESSIONS -> taskCommands.sessions(session);
            case RESUME -> taskCommands.resume(parsed.arguments(), session);
            case NEW -> taskCommands.newSession(session);
            case CONTINUE -> taskCommands.continueTask(parsed.arguments(),session);
            case TASK -> taskCommands.recover(parsed.arguments(), session);
            case REWIND -> taskCommands.rewind(parsed.arguments(), session);
            case REPORT -> ShellCommandResult.continueWith(
                    "REPORT", bounded(queries.report(before,
                            parsed.arguments().isEmpty()
                                    ? null : parsed.arguments().getFirst()), 96));
            case COMPACT -> compactions.compact(parsed.arguments(),before);
            case VERIFY -> verification(parsed.arguments(), session);
            case VERBOSE -> verbose(parsed.arguments());
            case HISTORY -> history(parsed.arguments(), history);
            case CLEAR -> {
                long generation = session.clearContext();
                yield ShellCommandResult.continueWith("CLEARED", List.of("Context generation: " + generation));
            }
            case EXIT -> new ShellCommandResult(ShellCommandResult.Action.EXIT, "EXIT", List.of());
            case DOCTOR -> ShellCommandResult.continueWith("DOCTOR", bounded(doctor.inspect()));
            default -> ShellCommandResult.continueWith("COMMAND_UNAVAILABLE",
                    List.of(parsed.command().token() + " is unavailable in this context."));
        };
    }

    public boolean isExitCommand(String input) {
        try {
            return parser.parse(input, false).command() == SlashCommand.EXIT;
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return false;
        }
    }

    private List<String> help() {
        List<String> lines = new ArrayList<>();
        lines.add("Interactive commands:");
        registry.all().forEach(spec -> {
            List<String> aliases = registry.tokensFor(spec.command()).stream()
                    .filter(token -> !token.equals(spec.command().token())).sorted().toList();
            String aliasNote = aliases.isEmpty() ? "" : " (aliases: " + String.join(", ", aliases) + ")";
            lines.add(spec.syntax() + aliasNote + " - " + spec.outputContract());
        });
        return lines;
    }

    private ShellCommandResult verification(List<String> arguments,
            InteractiveSession session) {
        InteractiveSession.Snapshot snapshot = session.snapshot();
        if (arguments.isEmpty() || "status".equals(arguments.getFirst())) {
            return ShellCommandResult.continueWith("VERIFY", List.of(
                    "Verification: " + snapshot.verificationMode()
                            + " (" + snapshot.verificationSource() + ")",
                    "Policy: " + snapshot.verificationPreset()));
        }
        if (snapshot.currentTaskId() != null) {
            throw new IllegalStateException("cannot change verification while a task is active");
        }
        String requested = arguments.getFirst();
        if (!java.util.Set.of("auto", "off").contains(requested)) {
            throw new IllegalArgumentException("verification mode must be auto or off");
        }
        String saveScope = null;
        if (arguments.size() > 1) {
            if (arguments.size() != 3 || !"--save".equals(arguments.get(1))
                    || !java.util.Set.of("project", "user").contains(arguments.get(2))) {
                throw new IllegalArgumentException(
                        "usage: /verify auto|off [--save project|user]");
            }
            saveScope = arguments.get(2);
        }
        var selection = "auto".equals(requested)
                ? com.paicli.interactive.verification.InteractiveVerificationPolicy
                    .auto(session.workspace())
                : new com.paicli.interactive.verification.InteractiveVerificationPolicy
                    .Selection("off", "none");
        String source = "session";
        if (saveScope != null) {
            try {
                source = verificationPreferences.save(selection, saveScope);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("verification preference was not saved");
            }
        }
        session.changeVerification(selection.mode(), selection.preset(), source);
        return ShellCommandResult.continueWith("VERIFY", List.of(
                "Verification: " + selection.mode() + " (" + source + ")",
                "Policy: " + selection.preset()));
    }

    private ShellCommandResult verbose(List<String> arguments) {
        String requested = arguments.isEmpty() ? "status" : arguments.getFirst();
        if ("status".equals(requested)) {
            return ShellCommandResult.continueWith("VERBOSE", List.of(
                    "Verbose: " + (displayPreferences.verbose() ? "on" : "off")));
        }
        if (!java.util.Set.of("on", "off").contains(requested)) {
            throw new IllegalArgumentException("verbose mode must be on, off, or status");
        }
        displayPreferences.setVerbose("on".equals(requested));
        return ShellCommandResult.continueWith("VERBOSE", List.of(
                "Verbose: " + requested));
    }

    private ShellCommandResult history(List<String> arguments,
            InteractiveHistoryStore history) {
        if (!arguments.isEmpty() && "clear".equals(arguments.getFirst())) {
            if (!history.clear()) {
                throw new IllegalStateException(history.warning() == null
                        ? "persistent history is unavailable" : history.warning());
            }
            return ShellCommandResult.continueWith("HISTORY_CLEARED",
                    List.of("Persistent history cleared."));
        }
        int limit = arguments.isEmpty() ? 20 : Integer.parseInt(arguments.getFirst());
        List<String> entries = history.recent(limit);
        if (!history.enabled()) {
            throw new IllegalStateException("persistent history unavailable: "
                    + (history.warning() == null ? "unknown history failure" : history.warning()));
        }
        if (entries.isEmpty()) {
            return ShellCommandResult.continueWith("HISTORY", List.of("History is empty."));
        }
        List<String> numbered = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            numbered.add((index + 1) + ": " + entries.get(index));
        }
        return ShellCommandResult.continueWith("HISTORY", bounded(numbered));
    }

    private static String shortId(String value) {
        return value.length() <= 16 ? value
                : value.substring(0, 7) + "…" + value.substring(value.length() - 8);
    }

    private static List<String> bounded(List<String> input) {
        return bounded(input,31);
    }

    // Reports combine the task projection, continuity, and bounded MCP/Skill audit sections.
    private static List<String> bounded(List<String> input,int maxLines) {
        if (input == null) return List.of("Doctor returned no result.");
        List<String> lines = new ArrayList<>(input.stream().limit(maxLines).map(value -> {
            String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
            return clean.substring(0, Math.min(clean.length(), 256));
        }).toList());
        if (input.size() > maxLines) lines.add("[output truncated]");
        return List.copyOf(lines);
    }
}
