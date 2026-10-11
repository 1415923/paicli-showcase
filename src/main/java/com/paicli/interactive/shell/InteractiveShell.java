package com.paicli.interactive.shell;

import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.command.SlashCommand;
import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.interactive.command.SlashCommandRouter;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.input.InteractiveInput;
import com.paicli.interactive.input.InteractiveInputParser;
import com.paicli.interactive.render.InteractiveShellRenderer;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.ForegroundTask;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.runtime.SteeringClassifier;
import com.paicli.runtime.FollowUpCanonicalizer;
import com.paicli.provider.ProviderFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class InteractiveShell {
    private final InteractiveShellConfig config;
    private final InteractiveInputCoordinator input;
    private final InteractiveShellRenderer renderer;
    private final InteractiveInputParser inputParser;
    private final SlashCommandRouter commandRouter;
    private final TaskSubmissionPort taskPort;
    private final InteractiveHistoryStore history;
    private final InteractivePromptRenderer prompts;
    private final Clock clock;
    private final SteeringClassifier steeringSanitizer = new SteeringClassifier();
    private final FollowUpCanonicalizer followUpSanitizer = new FollowUpCanonicalizer();
    private int rendererFailures;

    public InteractiveShell(InteractiveShellConfig config, ShellInputReader input,
            InteractiveShellRenderer renderer, InteractiveInputParser inputParser,
            SlashCommandRouter commandRouter, TaskSubmissionPort taskPort,
            InteractiveHistoryStore history, InteractivePromptRenderer prompts, Clock clock) {
        this(config, new InteractiveInputCoordinator(input), renderer, inputParser,
                commandRouter, taskPort, history, prompts, clock);
    }

    public InteractiveShell(InteractiveShellConfig config, InteractiveInputCoordinator input,
            InteractiveShellRenderer renderer, InteractiveInputParser inputParser,
            SlashCommandRouter commandRouter, TaskSubmissionPort taskPort,
            InteractiveHistoryStore history, InteractivePromptRenderer prompts, Clock clock) {
        this.config = config; this.input = input; this.renderer = renderer;
        this.inputParser = inputParser; this.commandRouter = commandRouter;
        this.taskPort = taskPort; this.history = history; this.prompts = prompts; this.clock = clock;
        this.input.bindRuntime(taskPort);
    }

    public InteractiveShellResult run() {
        InteractiveSession session = new InteractiveSession(
                "interactive-" + UUID.randomUUID(), config.workspace(), config.provider(),
                config.model(), config.approvalMode(), config.terminalMode(), clock.instant(),
                config.verificationMode(), config.verificationPreset(),
                config.verificationSource());
        int submitted = 0;
        session.start();
        session.changeProvider(config.provider(), config.model(), config.providerSource(),
                config.providerConfigurationFingerprint());
        render(() -> renderer.startup(config.applicationVersion(), session.snapshot()));
        try {
            taskPort.openSession(session);
        } catch (RuntimeException error) {
            render(() -> renderer.error(
                    "INTERACTIVE_RUNTIME_UNAVAILABLE", bounded(error.getMessage())));
        }
        if (config.startupCommand() != null) {
            try {
                ShellCommandResult result = commandRouter.route(
                        config.startupCommand(), session, history);
                if (!result.lines().isEmpty()) render(() -> renderer.lines(result.lines()));
            } catch (RuntimeException error) {
                render(() -> renderer.error(
                        "SAVED_SESSION_BINDING_FAILED", bounded(error.getMessage())));
            }
        }
        if (!history.enabled() && history.warning() != null) {
            render(() -> renderer.warning(history.warning()));
        }
        if (com.paicli.config.Config.PROVIDER_UNCONFIGURED.equals(config.provider())) {
            render(() -> renderer.warning("No production provider configured. Use /provider, /model, /doctor, /help, /status, or /exit."));
        }
        try {
            ShellCommandResult startupSessions = commandRouter.route(
                    "/sessions", session, history);
            if ("SESSIONS".equals(startupSessions.code())) {
                render(() -> renderer.warning(
                        "Saved Sessions are available. Use /sessions, then /resume <thread>."));
            }
        } catch (RuntimeException ignored) {
            // Startup remains available when durable Saved Session inspection fails.
        }
        boolean exit = false;
        try {
            while (!exit) {
                if (awaitForegroundTask(session)) break;
                String raw;
                InteractiveInputCoordinator.InputFrame frame;
                try {
                    frame = input.read(session, prompts);
                    raw = frame.value();
                }
                catch (InputInterruptedException error) {
                    render(() -> renderer.warning("Input interrupted; exiting PaiCLI."));
                    break;
                }
                catch (ProviderFactory.UnsupportedRuntimeProviderException error) {
                    render(() -> renderer.error(error.code(), bounded(error.getMessage())));
                    continue;
                }
                catch (com.paicli.runtime.TaskRecoveryRejectedException error) {
                    render(() -> renderer.error(error.code(), bounded(error.getMessage())));
                    continue;
                }
                catch (IllegalArgumentException error) {
                    render(() -> renderer.error("INVALID_INPUT", bounded(error.getMessage()))); continue;
                }
                render(renderer::flush);
                if(frame.state()==InteractiveInputCoordinator.State.FOLLOW_UP_CONFIRMATION_PENDING){
                    boolean startsTask = startsFollowUpTask(frame.confirmationKind(), raw);
                    if (startsTask && !admitPublicTask(session)) continue;
                    if (startsTask) renderForegroundPreparing();
                    TaskSubmissionResult confirmation=taskPort.respondFollowUpConfirmation(frame.approvalRequestId(),
                            frame.confirmationKind(),frame.confirmationVersion(),raw==null?"":raw);
                    if (confirmation.status() != TaskSubmissionResult.Status.PREPARING) {
                        render(()->renderer.taskResult(confirmation));
                    } else {
                        submitted++;
                    }
                    continue;
                }
                if (raw == null) {
                    if (session.snapshot().currentTaskId() != null) {
                        taskPort.requestCancellation();
                        taskPort.awaitIdle(java.time.Duration.ofSeconds(5));
                    }
                    break;
                }
                boolean terminalFollowUp=frame.terminalHandoffInvalidated()&&!raw.stripLeading().startsWith("/");
                if (frame.state() == InteractiveInputCoordinator.State.IDLE) {
                    try {
                        raw = readLogicalInput(raw, session);
                    } catch (InputInterruptedException error) {
                        render(() -> renderer.warning("Input interrupted; exiting PaiCLI."));
                        break;
                    } catch (IllegalArgumentException error) {
                        render(() -> renderer.error(
                                "INVALID_INPUT", bounded(error.getMessage())));
                        continue;
                    }
                    if (raw == null) break;
                }
                InteractiveInput parsed;
                try { parsed = inputParser.parse(raw); }
                catch (IllegalArgumentException error) {
                    render(() -> renderer.error("INVALID_INPUT", bounded(error.getMessage()))); continue;
                }
                if (parsed.kind() == InteractiveInput.Kind.EMPTY) continue;
                if(isExplicitSteering(raw)){
                    persistHistory(sanitizedSteeringHistory(raw));
                    TaskSubmissionResult steering=routeExplicitSteering(raw);
                    render(()->renderer.taskResult(steering));
                    continue;
                }
                if (parsed.kind() == InteractiveInput.Kind.SLASH_COMMAND) {
                    SlashCommandParser.ParsedSlashCommand slash;
                    try{slash=commandRouter.parse(raw,session.snapshot().currentTaskId()!=null);}
                    catch(IllegalArgumentException|IllegalStateException error){
                        render(()->renderer.error("COMMAND_REJECTED",bounded(error.getMessage())));continue;}
                    if(slash.command()==SlashCommand.CONTINUE){
                        if (!admitPublicTask(session)) continue;
                        String instruction=followUpSanitizer.sanitizeInstruction(String.join(" ",slash.arguments()));
                        persistHistory("/continue "+instruction);
                        renderForegroundPreparing();
                        TaskSubmissionResult followUp=taskPort.submitFollowUp(instruction,true,frame.followUpHeadToken());
                        if (followUp.status() != TaskSubmissionResult.Status.PREPARING) {
                            render(()->renderer.taskResult(followUp));
                        } else {
                            submitted++;
                        }
                        continue;
                    }
                    boolean recoversTask = slash.command() == SlashCommand.TASK
                            && !slash.arguments().isEmpty()
                            && "recover".equals(slash.arguments().getFirst());
                    if (recoversTask && !admitPublicTask(session)) continue;
                    if (recoversTask) renderForegroundPreparing();
                    persistHistory(raw);
                    try {
                        ShellCommandResult result = commandRouter.route(raw, session, history);
                        if (!"PREPARING".equals(result.code()) && !result.lines().isEmpty()) {
                            render(() -> renderer.lines(result.lines()));
                        } else if ("PREPARING".equals(result.code())) {
                            submitted++;
                        }
                        if ("HISTORY_CLEARED".equals(result.code())) {
                            if (input.clearNavigationHistory()) {
                                render(() -> renderer.lines(List.of(
                                        "Current terminal navigation history cleared.")));
                            } else {
                                render(() -> renderer.warning(
                                        "Persistent history was cleared, but terminal navigation history could not be cleared."));
                            }
                        }
                        exit = result.action() == ShellCommandResult.Action.EXIT;
                    } catch (IllegalArgumentException | IllegalStateException error) {
                        render(() -> renderer.error("COMMAND_REJECTED", bounded(error.getMessage())));
                    }
                    continue;
                }
                if (!admitPublicTask(session)) continue;
                TaskSubmissionResult inputAdmission = taskPort.admitOrdinaryInput(parsed.text());
                if (inputAdmission.status() != TaskSubmissionResult.Status.ACCEPTED) {
                    render(() -> renderer.taskResult(inputAdmission));
                    continue;
                }
                if("FOLLOW_UP_INPUT".equals(inputAdmission.code())||terminalFollowUp){
                    persistHistory(followUpSanitizer.sanitizeInstruction(parsed.text()));
                    renderForegroundPreparing();
                    TaskSubmissionResult followUp=taskPort.submitFollowUp(parsed.text(),false,frame.followUpHeadToken());
                    if (followUp.status() != TaskSubmissionResult.Status.PREPARING) {
                        render(()->renderer.taskResult(followUp));
                    } else {
                        submitted++;
                    }
                    continue;
                }
                persistHistory(raw);
                renderForegroundPreparing();
                TaskSubmissionResult result;
                try {
                    result = taskPort.submit(new InteractiveTaskRequest(
                            session.snapshot().sessionId(), config.workspace(), parsed.text(), parsed.fileReferences()));
                    if (result == null) throw new IllegalStateException("task submission returned no result");
                } catch (RuntimeException error) {
                    result = TaskSubmissionResult.rejected("TASK_SUBMISSION_FAILED", "Task submission failed.");
                }
                if (result.status() != TaskSubmissionResult.Status.PREPARING) {
                    TaskSubmissionResult rendered = result;
                    render(() -> renderer.taskResult(rendered));
                }
                if (result.status() == TaskSubmissionResult.Status.PREPARING
                        || result.status() == TaskSubmissionResult.Status.ACCEPTED) submitted++;
            }
        } finally {
            input.closing();
            if (session.snapshot().currentTaskId() != null) {
                taskPort.requestCancellation();
                taskPort.awaitIdle(java.time.Duration.ofSeconds(5));
            }
            try {
                taskPort.closeSession();
            } catch (RuntimeException error) {
                render(() -> renderer.error(
                        "INTERACTIVE_RUNTIME_CLOSE_FAILED", bounded(error.getMessage())));
            }
            if (session.snapshot().currentTaskId() == null) {
                session.close(Instant.now(clock));
            }
            render(renderer::closed);
            try {
                input.close();
            } catch (RuntimeException ignored) {
                // The runtime and renderer are already closed before terminal teardown.
            }
        }
        return new InteractiveShellResult(session.snapshot().status(), submitted, rendererFailures);
    }

    /**
     * Suspends the main composer until the current process-local execution reaches terminal.
     * Approval is the only foreground read and remains owned by this shell thread.
     *
     * @return true when foreground input or convergence failed and the Session must exit
     */
    private boolean awaitForegroundTask(InteractiveSession session) {
        ForegroundTask foreground = taskPort.activeForegroundTask().orElse(null);
        if (foreground == null) return false;
        boolean cancellationRequested = false;
        while (!foreground.terminal().isDone()) {
            var approval = taskPort.pendingApproval();
            if (approval.isPresent()) {
                InteractiveApprovalRequest request = approval.get();
                InteractiveInputCoordinator.ForegroundApprovalFrame frame;
                try {
                    frame = input.readApproval(session, prompts, request,
                            () -> !foreground.terminal().isDone()
                                    && taskPort.pendingApproval()
                                            .map(current -> current.requestId()
                                                    .equals(request.requestId()))
                                            .orElse(false));
                } catch (InputInterruptedException | IllegalStateException error) {
                    render(() -> renderer.warning(
                            "Foreground input interrupted; exiting PaiCLI."));
                    return true;
                }
                if (frame.input().status()
                        == ShellInputReader.ForegroundReadStatus.INVALIDATED) {
                    continue;
                }
                if (frame.input().status()
                        == ShellInputReader.ForegroundReadStatus.EOF) {
                    render(() -> renderer.warning(
                            "Approval input closed; exiting PaiCLI."));
                    return true;
                }
                if (frame.input().status()
                        == ShellInputReader.ForegroundReadStatus.CANCEL_TASK) {
                    if (!cancellationRequested) {
                        cancellationRequested = true;
                        taskPort.requestCancellation();
                    }
                    continue;
                }
                var interaction = taskPort.respondApproval(
                        frame.approvalRequestId(), frame.input().value());
                if (!interaction.lines().isEmpty()) {
                    render(() -> renderer.lines(interaction.lines()));
                }
                continue;
            }
            if (!cancellationRequested) {
                try {
                    ShellInputReader.ForegroundReadResult action =
                            input.pollForegroundAction(session, foreground.taskId(),
                                    () -> !foreground.terminal().isDone());
                    if (action.status()
                            == ShellInputReader.ForegroundReadStatus.CANCEL_TASK) {
                        cancellationRequested = true;
                        taskPort.requestCancellation();
                    } else if (action.status()
                            == ShellInputReader.ForegroundReadStatus.EOF) {
                        render(() -> renderer.warning(
                                "Foreground input closed; exiting PaiCLI."));
                        return true;
                    }
                    render(renderer::flush);
                } catch (InputInterruptedException error) {
                    render(() -> renderer.warning(
                            "Foreground input interrupted; exiting PaiCLI."));
                    return true;
                }
                continue;
            }
            try {
                foreground.terminal().get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Recheck the sole approval channel while waiting on the terminal Future.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                render(() -> renderer.warning(
                        "Foreground wait interrupted; exiting PaiCLI."));
                return true;
            } catch (ExecutionException error) {
                render(() -> renderer.error("FOREGROUND_TASK_FAILURE",
                        bounded(error.getCause() == null
                                ? error.getMessage() : error.getCause().getMessage())));
                return true;
            }
        }
        if (!taskPort.awaitIdle(Duration.ofSeconds(5))
                || session.snapshot().currentTaskId() != null
                || taskPort.activeForegroundTask().isPresent()) {
            render(() -> renderer.error("FOREGROUND_TASK_NOT_CONVERGED",
                    "Task terminal output completed before execution ownership converged."));
            return true;
        }
        TaskSubmissionResult terminal = foreground.terminal().getNow(null);
        if (terminal != null && ("TERMINAL_PERSISTENCE_FAILED".equals(terminal.code())
                || "EXECUTION_AUTHORITY_LOST".equals(terminal.code()))) {
            render(() -> renderer.error(terminal.code(), terminal.message()));
            render(() -> renderer.warning(
                    "Durable terminal state did not converge; exiting PaiCLI without a ready prompt."));
            return true;
        }
        render(renderer::endTaskOutput);
        render(renderer::flush);
        render(() -> renderer.lines(List.of("")));
        return false;
    }

    private void renderForegroundPreparing() {
        com.paicli.diagnostic.StageTrace.event(
                "FOREGROUND_OWNERSHIP_START", false,
                "transition", "IDLE_COMPOSER_TO_FOREGROUND_PENDING",
                "input_owner", "SHELL_SUBMISSION");
        render(renderer::beginTaskOutput);
        render(() -> renderer.taskResult(TaskSubmissionResult.preparing()));
        render(() -> renderer.lines(List.of(
                "[Hint] Esc cancels the current task; Ctrl+C exits PaiCLI.")));
    }

    private String readLogicalInput(String first, InteractiveSession session)
            throws InputInterruptedException {
        StringBuilder logical = new StringBuilder();
        String current = first;
        while (true) {
            boolean continued = hasContinuation(current);
            if (continued) current = current.substring(0, current.length() - 1);
            if (!logical.isEmpty()) logical.append('\n');
            logical.append(current);
            if (logical.length() > SlashCommandParser.MAX_INPUT_CHARS) {
                throw new IllegalArgumentException("input exceeds 8192 characters");
            }
            if (!continued) return logical.toString();
            current = input.readContinuation(session, prompts.secondary());
            if (current == null) return null;
        }
    }

    private static boolean hasContinuation(String value) {
        int slashes = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == '\\'; index--) slashes++;
        return slashes % 2 == 1;
    }

    private void persistHistory(String value) {
        boolean wasEnabled = history.enabled();
        history.append(value);
        if (wasEnabled && !history.enabled()) {
            render(() -> renderer.warning(history.warning() == null
                    ? "history persistence disabled" : history.warning()));
        }
    }
    private static boolean isExplicitSteering(String value){
        if(value==null)return false;String stripped=value.stripLeading();
        return stripped.equals("/steer")||stripped.startsWith("/steer ")||stripped.startsWith("steer>");
    }
    private static String steeringText(String value){
        String stripped=value==null?"":value.strip();
        String text=stripped.startsWith("/steer")?stripped.substring(6).strip()
                :stripped.startsWith("steer>")?stripped.substring(6).strip():"";
        return text;
    }
    private TaskSubmissionResult routeExplicitSteering(String value){
        String text=steeringText(value),normalized=text.toLowerCase(java.util.Locale.ROOT);
        if("status".equals(normalized))return taskPort.steeringStatus();
        if("history".equals(normalized))return taskPort.steeringHistory();
        if(normalized.startsWith("cancel ")){
            String id=text.substring(7).strip();
            return id.isEmpty()?TaskSubmissionResult.rejected("STEERING_ID_REQUIRED",
                    "Use /steer cancel <str_id>."):taskPort.cancelSteering(id);
        }
        return taskPort.submitSteering(text,true);
    }
    private String sanitizedSteeringHistory(String value){
        String text=steeringText(value),normalized=text.toLowerCase(java.util.Locale.ROOT);
        if("status".equals(normalized)||"history".equals(normalized)
                ||normalized.startsWith("cancel "))return value.strip();
        String sanitized=steeringSanitizer.sanitize(text);
        return value.stripLeading().startsWith("steer>")
                ?"steer> "+sanitized:"/steer "+sanitized;
    }
    private void render(Runnable action) {
        try { action.run(); } catch (RuntimeException error) { rendererFailures++; }
    }

    private boolean admitPublicTask(InteractiveSession session) {
        try {
            ProviderFactory.requirePublicRuntimeProvider(session.snapshot().provider());
            return true;
        } catch (ProviderFactory.UnsupportedRuntimeProviderException error) {
            render(() -> renderer.error(error.code(), bounded(error.getMessage())));
            return false;
        }
    }

    private static boolean startsFollowUpTask(String kind, String input) {
        String value = input == null ? ""
                : input.strip().toLowerCase(java.util.Locale.ROOT);
        return "INTENT".equals(kind)
                ? java.util.Set.of("c", "continue", "n", "new").contains(value)
                : "RISK".equals(kind) && java.util.Set.of("y", "yes").contains(value);
    }

    private static String bounded(String message) {
        if (message == null) return "operation failed";
        String clean = message.replace('\r', ' ').replace('\n', ' ');
        return clean.substring(0, Math.min(clean.length(), 256));
    }
}
