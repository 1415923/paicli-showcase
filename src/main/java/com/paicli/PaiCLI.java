package com.paicli;

import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProjectInstructions;
import com.paicli.demo.cli.CoreDemoCommandHandler;
import com.paicli.demo.cli.CoreDemoCommandParser;
import com.paicli.demo.cli.CoreDemoOutput;
import com.paicli.demo.runtime.CoreDemoRuntimeFactory;
import com.paicli.doctor.DoctorCommand;
import com.paicli.interactive.command.InteractiveDoctorPort;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.shell.InteractiveShellConfig;
import com.paicli.interactive.shell.InteractiveShellRunner;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.hook.HookConfigLoader;
import com.paicli.hook.HookDefinition;
import com.paicli.hook.HookRunner;
import com.paicli.hook.HookRuntime;
import com.paicli.model.AgentMessage;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.ExecutionProviderTelemetry;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CodingTaskRunner;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.CandidateVerificationGate;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.BoundedProcessRunner;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.VerificationService;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.skill.SkillContextBuilder;
import com.paicli.skill.SkillLoader;
import com.paicli.skill.SkillRuntime;
import com.paicli.skill.SkillSelector;
import com.paicli.session.AuditLog;
import com.paicli.session.MarkdownReport;
import com.paicli.session.SessionTrace;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.HookRunStore;
import com.paicli.state.RewindService;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.tool.GitDiffTool;
import com.paicli.tool.GrepTool;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.RunCommandTool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.WriteFileTool;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PaiCLI {

    private static final String VERSION = "1.2.0";

    public static void main(String[] args) {
        com.paicli.diagnostic.StageTrace.initialize(args);
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        if (args.length == 0) {
            return executeInteractive();
        }
        if ("--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return 0;
        }

        if ("--version".equals(args[0]) || "-v".equals(args[0])) {
            System.out.println("PaiCLI " + VERSION);
            return 0;
        }

        if ("skills".equals(args[0])) {
            try {
                Path workspace=Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
                Config config=new ConfigLoader(workspace).load();
                var result=new com.paicli.skill.SkillCommands(workspace,config.effectiveSkillsDirs())
                        .command(List.of(args).subList(1,args.length),false);
                result.lines().forEach(System.out::println);
                return "SKILLS".equals(result.code())?0:2;
            }catch(Exception error){System.err.println("Skill command failed: "+error.getMessage());return 2;}
        }

        if ("doctor".equals(args[0])) {
            if (args.length > 2 || (args.length == 2 && !"--plain".equals(args[1]))) {
                System.err.println("Error: doctor accepts only the optional --plain flag");
                return 2;
            }
            try {
                Path workspace = Paths.get(System.getProperty("user.dir"))
                        .toAbsolutePath().normalize();
                return new DoctorCommand(workspace)
                        .execute(new PrintWriter(System.out, true));
            } catch (RuntimeException error) {
                System.err.println("Error: doctor diagnostic failed");
                return 1;
            }
        }

        if ("demo".equals(args[0])) {
            return executeCoreDemo(args);
        }

        if ("compact".equals(args[0])) {
            Path workspace = Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath().normalize();
            if(args.length<2){
                System.err.println("Error: usage: compact status [--task <task_id>] [--run <run_id>] | "
                        +"compact preview --task <task_id> --run <run_id> [--operation <OPERATION>] | "
                        +"compact apply <ctx_id>");
                return 2;
            }
            var result=new com.paicli.interactive.command.InteractiveCompactionCommandService(workspace)
                    .compactNonInteractive(List.of(args).subList(1,args.length));
            System.out.println(result.code());
            result.lines().forEach(System.out::println);
            return java.util.Set.of("COMPACTION_STATUS","COMPACTION_PREPARED","COMPACTION_READY",
                    "COMPACTION_IN_PROGRESS","COMPACTION_NOT_APPLICABLE").contains(result.code())?0:1;
        }

        CliRequest request;
        try {
            request = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return 2;
        }

        Path workspace = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        if (request.resumeThreadSelector() != null) {
            return executeInteractive("/resume " + request.resumeThreadSelector());
        }
        if (request.rewindSnapshotId() != null) {
            return executeRewind(workspace, request.rewindSnapshotId());
        }

        String taskId = CanonicalId.newId(EntityFamily.TASK).value();

        try {
            Config config = applyCliOverrides(
                    new ConfigLoader(workspace).load(), request);
            PersistenceSanitizer sanitizer = new PersistenceSanitizer();
            Config providerConfig=config.withSystemPrompt(sanitizer.sanitizeAndTruncate(
                    config.effectiveSystemPrompt(),PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES));
            ModelProvider rawProvider = ProviderFactory.create(providerConfig);

            try (StateStore stateStore = new StateStore(workspace)) {
                String goal = request.goal();
                ProjectInstructions instructions =
                        ProjectInstructions.load(workspace, config);

                printRunHeader(taskId, workspace, goal, config, false);
                printInstructionSummary(instructions);

                System.out.println("Active provider: "
                        + rawProvider.getClass().getSimpleName());

                SafetyPolicy policy = new SafetyPolicy(
                        workspace,
                        config.commandTimeoutSeconds(),
                        config.maxOutputBytes());
                AuditLog auditLog = new AuditLog(
                        workspace.resolve(".paicli").resolve("audit"), taskId);
                FileSnapshotService snapshotService = new FileSnapshotService(
                        workspace, stateStore.snapshots);
                ControlPlanePaths controlPlanePaths = new ControlPlanePaths(
                        workspace,
                        config.effectiveSkillsDirs(),
                        List.of(config.effectiveHooksConfigPath()));
                SkillRuntime skillRuntime = config.isSkillsEnabled()
                        ? new SkillRuntime(
                                new SkillLoader(workspace, stateStore.skillLoads, sanitizer),
                                new SkillSelector(stateStore.skillSelections, sanitizer),
                                new SkillContextBuilder(sanitizer),
                                stateStore.skillSelections,
                                config)
                        : null;
                HookRuntime hookRuntime = null;
                if (config.isHooksEnabled()) {
                    List<HookDefinition> definitions = new HookConfigLoader(
                            workspace,
                            config.effectiveHookTimeoutSeconds(),
                            config.effectiveHookFailurePolicy())
                            .load(Path.of(config.effectiveHooksConfigPath()));
                    HookRunner hookRunner = new HookRunner(
                            stateStore.hookRuns,
                            new BoundedProcessRunner(
                                    workspace, sanitizer,
                                    config.effectiveMaxHookOutputBytes()),
                            sanitizer,
                            config.effectiveMaxHookOutputBytes());
                    hookRuntime = new HookRuntime(
                            definitions, hookRunner, stateStore.hookRuns);
                }
                RuntimeManifest runtimeManifest =
                        new RuntimeManifestHasher(workspace).compute(config);

                java.util.function.Supplier<ProviderCallContext> telemetryContext = () -> {
                    try {
                        var pointer = stateStore.r1aDurable.readContextPointer(
                                CanonicalId.parse(taskId)).orElse(null);
                        return new ProviderCallContext(null, taskId,
                                pointer == null ? null : pointer.ownerRunId().value(),
                                pointer == null ? null : pointer.generationId().value());
                    } catch (RuntimeException | java.sql.SQLException ignored) {
                        return new ProviderCallContext(null, taskId, null, null);
                    }
                };
                ExecutionProviderTelemetry.Providers instrumented =
                        ExecutionProviderTelemetry.instrument(rawProvider, rawProvider,
                                stateStore.providerTelemetry, config.effectiveProvider(),
                                config.model(), telemetryContext,
                                config.isTaskContinuityEnabled());
                ModelProvider provider = new com.paicli.context.ContextualModelProvider(
                        instrumented.chat(), stateStore, workspace, taskId,
                        config.effectiveProvider(), config.model(),
                        com.paicli.context.ContextModels.OperationKind.CHAT,
                        com.paicli.context.ContextualModelProvider.capability(
                                config.effectiveProvider(),config.model(),
                                config.effectiveContextWindowTokens()), false,
                        config.effectiveSystemPrompt());
                ModelProvider planningProvider = new com.paicli.context.ContextualModelProvider(
                        instrumented.plan(), stateStore, workspace, taskId,
                        config.effectiveProvider(), config.model(),
                        com.paicli.context.ContextModels.OperationKind.PLAN,
                        com.paicli.context.ContextualModelProvider.capability(
                                config.effectiveProvider(),config.model(),
                                config.effectiveContextWindowTokens()), false,
                        config.effectiveSystemPrompt());
                Config effectiveConfig = config;
                CodingTaskRunner runner = new CodingTaskRunner(
                        stateStore,
                        new PlanService(planningProvider, stateStore.plans),
                        new VerificationService(
                                workspace,
                                stateStore.verifications,
                                sanitizer,
                                config.effectiveVerificationTimeoutSeconds(),
                                config.effectiveMaxVerificationOutputBytes()),
                        managedTaskId -> new AgentLoop(
                                provider,
                                createToolRegistry(
                                        policy, snapshotService, managedTaskId,
                                        controlPlanePaths),
                                effectiveConfig.effectiveMaxIterations(),
                                new ApprovalPolicy(effectiveConfig, managedTaskId),
                                auditLog,
                                stateStore,
                                managedTaskId,
                                effectiveConfig.effectiveProvider(),
                                effectiveConfig.model()),
                        config,
                        sanitizer,
                        skillRuntime,
                        hookRuntime,
                        runtimeManifest,
                        CodingTaskObserver.NOOP,
                        CandidateVerificationGate.ALLOW,
                        new InstructionManifestRuntime(stateStore, workspace));
                var continuityStatus =
                        com.paicli.runtime.ProductionTaskContinuityWiring.configure(
                                runner, config, stateStore);

                Instant startedAt = Instant.now();
                CodingTaskResult result = runner.run(taskId, goal);
                Instant completedAt = Instant.now();

                TaskStore.TaskRecord task = stateStore.tasks.findById(taskId)
                        .orElseThrow();
                CodingRunStore.CodingRunRecord codingRun = stateStore.codingRuns
                        .findById(result.runId()).orElseThrow();
                List<AgentMessage> persistedMessages =
                        stateStore.events.findMessages(taskId);
                List<String> reportMessages = toReportMessages(persistedMessages);
                String artifactAnswer = persistedMessages.stream()
                        .filter(message -> message.type()
                                == AgentMessage.MessageType.FINAL)
                        .reduce((first, second) -> second)
                        .map(AgentMessage::content)
                        .orElse(result.summary());
                List<String> selectedSkills = stateStore.skillSelections
                        .findSelectedByRun(result.runId()).stream()
                        .map(record -> record.skillName())
                        .distinct()
                        .sorted()
                        .toList();
                List<HookRunStore.HookRunRecord> hookRuns =
                        stateStore.hookRuns.findByRun(result.runId());
                List<String> hookSummary = hookRuns.stream()
                        .map(run -> run.hookId() + "=" + run.status())
                        .toList();
                long hookWarnings = hookRuns.stream()
                        .filter(PaiCLI::isHookWarningOrFailure)
                        .count();

                System.out.println("--- Result ---");
                System.out.println("Run ID     : " + result.runId());
                System.out.println("Iterations : " + codingRun.lastIteration());
                System.out.println("Completed  : "
                        + TaskStore.STATUS_DONE.equals(result.finalStatus()));
                System.out.println("Verification: " + result.verificationStatus());
                System.out.println("Repairs    : " + result.repairAttempts());
                System.out.println("Skills     : "
                        + (selectedSkills.isEmpty() ? "none" : String.join(", ", selectedSkills)));
                System.out.println("Hook runs  : " + hookRuns.size());
                System.out.println("Hook issues: " + hookWarnings);
                var continuitySegments = stateStore.taskContinuity.findByTask(taskId);
                System.out.println("Continuity : " + continuityStatus.displayState());
                if (continuityStatus.enabled()) {
                    System.out.println("Policy ID  : " + continuityStatus.policyIdentity());
                    System.out.println("Segments   : " + continuitySegments.size());
                    System.out.println("Continuations: "
                            + Math.max(0, continuitySegments.size() - 1));
                }
                if (result.summary() != null) {
                    System.out.println("Answer     : " + result.summary());
                }
                System.out.println();

                Path sessionsDir = workspace.resolve(".paicli").resolve("sessions");
                SessionTrace trace = new SessionTrace(sessionsDir, taskId);
                trace.writeStart(task.goal());
                for (String message : reportMessages) {
                    if (message.startsWith("ACTION: ")) {
                        trace.writeAction(
                                "tool", message.substring(8), codingRun.lastIteration());
                    } else if (message.startsWith("OBSERVATION: ")) {
                        trace.writeObservation(
                                "tool", message.substring(13), codingRun.lastIteration());
                    }
                }
                trace.writeFinal(
                        artifactAnswer != null ? artifactAnswer : "(no final answer)",
                        codingRun.lastIteration());
                System.out.println("Session trace: " + trace.getTraceFile());

                Path reportsDir = workspace.resolve("reports").resolve("runs");
                MarkdownReport report = new MarkdownReport(reportsDir, taskId);
                report.write(
                        task.goal(), startedAt, completedAt,
                        codingRun.lastIteration(), reportMessages,
                        artifactAnswer,
                        TaskStore.STATUS_DONE.equals(result.finalStatus()),
                        new MarkdownReport.RuntimeSummary(
                                selectedSkills, hookSummary, hookWarnings));
                System.out.println("Report       : " + report.getReportFile());
                System.out.println("Audit log    : " + auditLog.getAuditFile());
                System.out.println("State DB     : "
                        + workspace.resolve(".paicli").resolve("state")
                        .resolve("paicli.db"));
                System.out.println("Checkpoint   : "
                        + workspace.resolve(".paicli").resolve("checkpoints")
                        .resolve(taskId + ".md"));
            }
            return 0;
        } catch (ProviderFactory.UnsupportedRuntimeProviderException e) {
            System.err.println("Error: " + e.code() + ": " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + conciseError(e));
            return 1;
        }
    }

    private static int executeCoreDemo(String[] args) {
        try {
            return new CoreDemoCommandHandler(new CoreDemoCommandParser(),
                    CoreDemoRuntimeFactory.production(), new CoreDemoOutput()).execute(
                    args, new PrintWriter(System.out, true),
                    new PrintWriter(System.err, true));
        } catch (Exception error) {
            System.err.println("Core Demo failed: INTERNAL_ERROR");
            System.err.println("PAICLI_DEMO_RESULT={\"schemaVersion\":1,\"status\":\"INTERNAL_ERROR\",\"exitCode\":8}");
            return 8;
        }
    }

    private static String conciseError(Exception e) {
        String message = e.getMessage();
        if ((message == null || message.isBlank()) && e.getCause() != null) {
            message = e.getCause().getMessage();
        }
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.replace((char) 13, ' ').replace((char) 10, ' ');
    }

    private static CliRequest parseArgs(String[] args) {
        String providerOverride = null;
        String verificationCommandOverride = null;
        Integer maxRepairAttemptsOverride = null;
        Boolean skillsOverride = null;
        Boolean hooksOverride = null;
        String hooksConfigOverride = null;
        boolean noPlan = false;
        boolean noRepair = false;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--provider" -> {
                    providerOverride = requireOptionValue(args, ++i, "--provider");
                }
                case "--verify", "--verify-command" -> {
                    verificationCommandOverride =
                            requireOptionValue(args, ++i, args[i - 1]);
                }
                case "--max-repair-attempts" -> {
                    String value = requireOptionValue(
                            args, ++i, "--max-repair-attempts");
                    try {
                        maxRepairAttemptsOverride = Integer.valueOf(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "--max-repair-attempts requires an integer");
                    }
                    if (maxRepairAttemptsOverride < 1
                            || maxRepairAttemptsOverride
                            > Config.MAX_REPAIR_ATTEMPTS) {
                        throw new IllegalArgumentException(
                                "--max-repair-attempts must be between 1 and "
                                        + Config.MAX_REPAIR_ATTEMPTS);
                    }
                }
                case "--no-plan" -> noPlan = true;
                case "--no-repair" -> noRepair = true;
                case "--skills" -> skillsOverride = true;
                case "--no-skills" -> skillsOverride = false;
                case "--hooks" -> hooksOverride = true;
                case "--no-hooks" -> hooksOverride = false;
                case "--hooks-config" -> hooksConfigOverride =
                        requireOptionValue(args, ++i, "--hooks-config");
                default -> positional.add(args[i]);
            }
        }

        if (positional.isEmpty()) {
            throw new IllegalArgumentException("no task specified");
        }
        if ("resume".equals(positional.getFirst())) {
            if (positional.size() != 2) {
                throw new IllegalArgumentException(
                        "resume requires exactly one Thread ID or --last");
            }
            return new CliRequest(
                    null, positional.get(1), null, providerOverride,
                    verificationCommandOverride, maxRepairAttemptsOverride,
                    noPlan, noRepair, skillsOverride, hooksOverride,
                    hooksConfigOverride);
        }
        if ("rewind".equals(positional.getFirst())) {
            if (positional.size() != 2) {
                throw new IllegalArgumentException(
                        "rewind requires exactly one snapshotId");
            }
            return new CliRequest(
                    null, null, positional.get(1), providerOverride,
                    verificationCommandOverride, maxRepairAttemptsOverride,
                    noPlan, noRepair, skillsOverride, hooksOverride,
                    hooksConfigOverride);
        }
        return new CliRequest(
                String.join(" ", positional), null, null, providerOverride,
                verificationCommandOverride, maxRepairAttemptsOverride,
                noPlan, noRepair, skillsOverride, hooksOverride,
                hooksConfigOverride);
    }

    private static String requireOptionValue(
            String[] args, int valueIndex, String option) {
        if (valueIndex >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[valueIndex];
    }

    private static Config applyCliOverrides(Config config, CliRequest request) {
        return new Config(
                config.workspace(),
                config.commandTimeoutSeconds(),
                config.maxOutputBytes(),
                config.maxWriteFileBytes(),
                config.approvalMode(),
                config.maxProjectInstructionChars(),
                request.providerOverride() != null
                        ? request.providerOverride() : config.provider(),
                config.model(),
                config.baseUrl(),
                config.apiKeyEnv(),
                config.systemPrompt(),
                config.maxIterations(),
                request.verificationCommandOverride() != null
                        ? request.verificationCommandOverride()
                        : config.verificationCommand(),
                config.verificationTimeoutSeconds(),
                config.maxVerificationOutputBytes(),
                request.maxRepairAttemptsOverride() != null
                        ? request.maxRepairAttemptsOverride()
                        : config.maxRepairAttempts(),
                request.noPlan() ? false : config.enablePlan(),
                config.enableVerify(),
                request.noRepair() ? false : config.enableRepair(),
                request.skillsOverride() != null
                        ? request.skillsOverride() : config.enableSkills(),
                config.skillsDirs(),
                config.maxSkillContextChars(),
                config.maxSelectedSkills(),
                request.hooksOverride() != null
                        ? request.hooksOverride() : config.enableHooks(),
                request.hooksConfigOverride() != null
                        ? request.hooksConfigOverride() : config.hooksConfigPath(),
                config.hookTimeoutSeconds(),
                config.maxHookOutputBytes(),
                config.hookFailurePolicy(),
                config.contextWindowTokens(),
                config.providerPreset(),
                config.thinking(),
                config.reasoningEffort(),
                config.credentialRef());
    }

    private static int executeRewind(Path workspace, String snapshotId) {
        try (StateStore stateStore = new StateStore(workspace)) {
            RewindService.RewindResult result =
                    new RewindService(workspace, stateStore.snapshots)
                            .rewind(snapshotId);
            System.out.println("PaiCLI " + VERSION);
            System.out.println("Mode       : rewind");
            System.out.println("Snapshot ID: " + result.snapshotId());
            System.out.println("File path  : " + result.filePath());
            System.out.println("Operation  : " + result.operation());
            System.out.println("Result     : "
                    + (result.deleted() ? "deleted file" : "restored file"));
            System.out.println("State DB   : "
                    + workspace.resolve(".paicli").resolve("state")
                    .resolve("paicli.db"));
            return 0;
        } catch (Exception e) {
            System.err.println("Error: rewind failed: " + e.getMessage());
            return 1;
        }
    }

    static ToolRegistry createToolRegistry(
            SafetyPolicy policy,
            FileSnapshotService snapshotService,
            String taskId) {
        return createToolRegistry(policy, snapshotService, taskId, null);
    }

    static ToolRegistry createToolRegistry(
            SafetyPolicy policy,
            FileSnapshotService snapshotService,
            String taskId,
            ControlPlanePaths controlPlanePaths) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(policy));
        registry.register(new ReadFileTool(policy));
        registry.register(new GrepTool(policy));
        registry.register(new RunCommandTool(policy));
        registry.register(new GitDiffTool(policy));
        registry.register(new WriteFileTool(
                policy, snapshotService, taskId, null, controlPlanePaths));
        return registry;
    }

    private static boolean isHookWarningOrFailure(
            HookRunStore.HookRunRecord run) {
        return !HookRunStore.STATUS_PASSED.equals(run.status());
    }

    private static List<String> toReportMessages(
            List<AgentMessage> messages) {
        return messages.stream()
                .map(message -> message.type().name() + ": " + message.content())
                .toList();
    }

    private static void printRunHeader(
            String taskId, Path workspace, String goal,
            Config config, boolean resumed) {
        System.out.println("PaiCLI " + VERSION);
        System.out.println("Task ID  : " + taskId);
        System.out.println("Workspace: " + workspace);
        System.out.println("Goal     : " + goal);
        System.out.println("Provider : " + config.effectiveProvider());
        System.out.println("Approval : " + config.approvalMode());
        if (resumed) {
            System.out.println("Mode     : resume");
        }
        System.out.println();
    }

    private static void printInstructionSummary(
            ProjectInstructions instructions) {
        if (!instructions.isEmpty()) {
            System.out.println(
                    "Project instructions loaded from: "
                            + instructions.sources());
            if (instructions.wasTruncated()) {
                System.out.println("  (truncated from "
                        + instructions.originalLength()
                        + " to " + instructions.truncatedTo() + " chars)");
            }
        }
    }

    private static int executeInteractive() {
        return executeInteractive(null);
    }

    private static int executeInteractive(String startupCommand) {
        String userDir = System.getProperty("user.dir");
        com.paicli.diagnostic.StageTrace.event(
                "WORKSPACE_RESOLUTION_START", false,
                "source", "CURRENT_DIRECTORY_USER_DIR",
                "raw_user_dir", userDir);
        com.paicli.diagnostic.StageTrace.event(
                "WORKSPACE_CANDIDATE", false,
                "candidate_source", "CURRENT_DIRECTORY_USER_DIR",
                "candidate", userDir);
        Path workspace = Paths.get(userDir).toAbsolutePath().normalize();
        com.paicli.diagnostic.StageTrace.event(
                "WORKSPACE_SELECTED", true,
                "selection_source", "CURRENT_DIRECTORY_USER_DIR",
                "selected", com.paicli.diagnostic.StageTrace.canonicalPath(workspace),
                "explicit_argument", false,
                "project_marker_discovery", false,
                "user_home_fallback", false);
        try {
            ConfigLoader.Resolution resolution = new ConfigLoader(workspace).resolve();
            Config config = resolution.config();
            var verification = com.paicli.interactive.verification
                    .InteractiveVerificationPolicy.fromConfig(config);
            InteractiveShellConfig shellConfig = new InteractiveShellConfig(
                    VERSION, workspace, config.effectiveProvider(), config.model(),
                    config.approvalMode(), InteractiveSession.TerminalMode.INTERACTIVE,
                    config.hasConfiguredProvider() ? "configured" : "none",
                    verification.mode(), verification.preset(),
                    resolution.verificationSource().label(), startupCommand);
            InteractiveDoctorPort doctor = () -> {
                java.io.StringWriter buffer = new java.io.StringWriter();
                new DoctorCommand(workspace).execute(new PrintWriter(buffer, true));
                return buffer.toString().lines().limit(32).toList();
            };
            new InteractiveShellRunner().runSystemRuntime(
                    shellConfig, InteractiveHistoryStore.defaultStore(), doctor);
            return 0;
        } catch (Exception error) {
            System.err.println("Error: interactive shell could not start");
            return 1;
        }
    }
    private static void printUsage() {
        System.out.println("PaiCLI " + VERSION
                + " - Java Coding Agent Runtime");
        System.out.println();
        System.out.println("Usage:");
        System.out.println(
                "  paicli                            Enter interactive mode");
        System.out.println(
                "  paicli <task>                     Run coding task / verify / repair");
        System.out.println(
                "  paicli --provider ollama <task>   Run with Ollama");
        System.out.println(
                "  paicli --verify <command> <task>  Verify after execution");
        System.out.println(
                "  paicli --verify-command <command> Alias for --verify");
        System.out.println(
                "  paicli --max-repair-attempts <n>  Set repair limit (1..5)");
        System.out.println(
                "  paicli --no-plan <task>           Disable configured Advisory Plan");
        System.out.println(
                "  paicli --no-repair <task>         Disable repair");
        System.out.println(
                "  paicli --skills|--no-skills       Enable or disable Skills");
        System.out.println("  paicli skills list|show|init|import  Manage local Skill packages (also /skills)");
        System.out.println(
                "  paicli --hooks|--no-hooks         Enable or disable Hooks");
        System.out.println(
                "  paicli --hooks-config <path>      Select workspace hooks config");
        System.out.println(
                "  paicli resume <thread>            Bind a Saved Session");
        System.out.println(
                "  paicli resume --last              Bind the latest Saved Session");
        System.out.println(
                "  paicli rewind <snapshotId>        Rewind a file snapshot");
        System.out.println(
                "  paicli doctor [--plain]           Read-only environment diagnostics");
        System.out.println(
                "  paicli demo core --headless       Fixed offline deterministic Core Demo");
        System.out.println(
                "  paicli --version                  Show version");
        System.out.println(
                "  paicli --help                     Show this help");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println(
                "  .paicli/config.json    Project-level config");
        System.out.println();
        System.out.println(
                "Providers: ollama, openai; mock is development-only and unsupported here");
        System.out.println(
                "Approval modes: plan, read-only, on-request, workspace-write, deny");
        System.out.println(
                "Interactive: /sessions /resume /new /task recover /provider /model /mode /plan /diff /tasks /rewind /report /doctor");
    }

    private record CliRequest(
            String goal,
            String resumeThreadSelector,
            String rewindSnapshotId,
            String providerOverride,
            String verificationCommandOverride,
            Integer maxRepairAttemptsOverride,
            boolean noPlan,
            boolean noRepair,
            Boolean skillsOverride,
            Boolean hooksOverride,
            String hooksConfigOverride
    ) {
    }
}
