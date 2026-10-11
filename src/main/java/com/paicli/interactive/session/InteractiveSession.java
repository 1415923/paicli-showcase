package com.paicli.interactive.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public final class InteractiveSession {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public enum Status { CREATED, ACTIVE, CLOSING, CLOSED }
    public enum TerminalMode { INTERACTIVE, PLAIN, DUMB }
    public enum CancellationState { IDLE, REQUESTED, FORCE_REQUESTED }

    private final String sessionId;
    private final Path workspace;
    private String provider;
    private String model;
    private String providerSource;
    private String providerConfigurationFingerprint;
    private String approvalMode;
    private String verificationMode;
    private String verificationPreset;
    private String verificationSource;
    private final TerminalMode terminalMode;
    private final Instant startedAt;
    private Status status = Status.CREATED;
    private String currentTaskId;
    private String currentRunId;
    private String lastTaskId;
    private long contextGeneration = 1;
    private CancellationState cancellationState = CancellationState.IDLE;
    private Instant endedAt;
    private SessionBinding binding;

    public InteractiveSession(String sessionId, Path workspace, String provider, String model,
            String approvalMode, TerminalMode terminalMode, Instant startedAt) {
        this(sessionId, workspace, provider, model, approvalMode, terminalMode,
                startedAt, "off", "none", "none");
    }

    public InteractiveSession(String sessionId, Path workspace, String provider, String model,
            String approvalMode, TerminalMode terminalMode, Instant startedAt,
            String verificationMode, String verificationPreset) {
        this(sessionId, workspace, provider, model, approvalMode, terminalMode,
                startedAt, verificationMode, verificationPreset, "session");
    }

    public InteractiveSession(String sessionId, Path workspace, String provider, String model,
            String approvalMode, TerminalMode terminalMode, Instant startedAt,
            String verificationMode, String verificationPreset,
            String verificationSource) {
        if (sessionId == null || !SAFE_ID.matcher(sessionId).matches()) throw new IllegalArgumentException("invalid sessionId");
        this.sessionId=sessionId;
        this.workspace=Objects.requireNonNull(workspace,"workspace").toAbsolutePath().normalize();
        this.provider=bounded(provider,"provider",256);
        this.model=model==null?null:bounded(model,"model",256);
        this.providerSource = "unconfigured".equals(provider) ? "none" : "configured";
        this.approvalMode=bounded(approvalMode,"approvalMode",64);
        this.verificationMode=verificationMode(verificationMode);
        this.verificationPreset=bounded(verificationPreset,"verificationPreset",128);
        this.verificationSource=bounded(verificationSource,"verificationSource",32);
        this.terminalMode=Objects.requireNonNull(terminalMode,"terminalMode");
        this.startedAt=Objects.requireNonNull(startedAt,"startedAt");
        this.binding=SessionBinding.unbound(startedAt);
    }

    public synchronized void start() { require(Status.CREATED); status=Status.ACTIVE; }

    public synchronized void startTask(String taskId) {
        require(Status.ACTIVE);
        if(currentTaskId!=null)throw new IllegalStateException("a task is already active");
        currentTaskId=id(taskId,"taskId"); currentRunId=null;
        cancellationState=CancellationState.IDLE;
    }

    public synchronized void startTask(String taskId, String runId) {
        startTask(taskId);
        bindRun(taskId, runId);
    }

    public synchronized void bindRun(String taskId, String runId) {
        require(Status.ACTIVE);
        if(!Objects.equals(currentTaskId,id(taskId,"taskId")))
            throw new IllegalStateException("task is not active");
        String safeRun=id(runId,"runId");
        if(currentRunId!=null&&!currentRunId.equals(safeRun))
            throw new IllegalStateException("active task already has a different run");
        currentRunId=safeRun;
    }

    public synchronized void finishTask() {
        if(currentTaskId==null)throw new IllegalStateException("no active task");
        finishTask(currentTaskId);
    }

    public synchronized void finishTask(String taskId) {
        require(Status.ACTIVE);
        if(!Objects.equals(currentTaskId,id(taskId,"taskId")))
            throw new IllegalStateException("task is not active");
        lastTaskId=currentTaskId; currentTaskId=null; currentRunId=null;
        cancellationState=CancellationState.IDLE;
    }

    public synchronized String changeApprovalMode(String requested) {
        require(Status.ACTIVE);
        String next = mode(requested);
        if (currentTaskId != null && modeRank(next) > modeRank(approvalMode)) {
            throw new IllegalStateException("cannot increase permission while a task is active");
        }
        approvalMode = next;
        return approvalMode;
    }
    public synchronized String changeVerification(String mode, String preset) {
        return changeVerification(mode, preset, "session");
    }
    public synchronized String changeVerification(String mode, String preset, String source) {
        require(Status.ACTIVE);
        if (currentTaskId != null) {
            throw new IllegalStateException("cannot change verification while a task is active");
        }
        verificationMode = verificationMode(mode);
        verificationPreset = bounded(preset, "verificationPreset", 128);
        verificationSource = bounded(source, "verificationSource", 32);
        return verificationMode;
    }
    public synchronized void changeProvider(String nextProvider, String nextModel,
            String nextSource) {
        changeProvider(nextProvider, nextModel, nextSource, null);
    }
    public synchronized void changeProvider(String nextProvider, String nextModel,
            String nextSource, String nextConfigurationFingerprint) {
        require(Status.ACTIVE);
        if (currentTaskId != null) throw new IllegalStateException("cannot change provider while a task is active");
        provider = bounded(nextProvider, "provider", 256);
        model = nextModel == null ? null : bounded(nextModel, "model", 256);
        providerSource = bounded(nextSource, "providerSource", 32);
        providerConfigurationFingerprint = nextConfigurationFingerprint == null ? null
                : fingerprint(nextConfigurationFingerprint);
    }
    public synchronized long clearContext() {
        require(Status.ACTIVE);
        if(currentTaskId!=null)throw new IllegalStateException("cannot clear while a task is active");
        return ++contextGeneration;
    }

    public synchronized void bindThread(SessionBinding next) {
        require(Status.ACTIVE);
        if (currentTaskId != null) {
            throw new IllegalStateException("cannot change Thread binding while a task is active");
        }
        if (next == null || next.state() == SessionBinding.State.UNBOUND) {
            throw new IllegalArgumentException("Bound Thread state required");
        }
        binding = next;
    }

    public synchronized void refreshBinding(SessionBinding next) {
        require(Status.ACTIVE);
        if (next == null || binding.state() == SessionBinding.State.UNBOUND
                || next.state() == SessionBinding.State.UNBOUND
                || !Objects.equals(binding.threadId(), next.threadId())) {
            throw new IllegalArgumentException("Binding refresh must retain the selected Thread");
        }
        binding = next;
    }

    public synchronized void clearThreadBinding(Instant at) {
        require(Status.ACTIVE);
        if (currentTaskId != null) {
            throw new IllegalStateException("cannot clear Thread binding while a task is active");
        }
        binding = SessionBinding.unbound(Objects.requireNonNull(at, "at"));
    }

    public synchronized CancellationState requestCancellation() {
        require(Status.ACTIVE);
        if(currentTaskId==null)throw new IllegalStateException("no active task");
        cancellationState = cancellationState == CancellationState.IDLE
                ? CancellationState.REQUESTED : CancellationState.FORCE_REQUESTED;
        return cancellationState;
    }

    public synchronized void close(Instant when) {
        if(status==Status.CLOSED)return;
        if(currentTaskId!=null)throw new IllegalStateException("cannot close with an active task");
        status=Status.CLOSING; endedAt=Objects.requireNonNull(when,"when"); status=Status.CLOSED;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sessionId,workspaceLabel(),
                provider,model,providerSource,approvalMode,verificationMode,
                verificationPreset,verificationSource,terminalMode,status,currentTaskId,currentRunId,lastTaskId,
                contextGeneration,cancellationState,binding,startedAt,endedAt,
                providerConfigurationFingerprint);
    }

    public Path workspace() { return workspace; }
    @Override public synchronized String toString(){return "InteractiveSession[sessionId="+sessionId+", status="+status+", workspace=<bounded>]";}

    private String workspaceLabel() {
        Path name = workspace.getFileName();
        if (name == null) return "workspace";
        String clean = name.toString().replaceAll("[\\p{Cntrl}]", "?");
        return clean.isBlank() ? "workspace" : clean.substring(0, Math.min(clean.length(), 128));
    }

    private static String mode(String value) {
        if (!java.util.Set.of("plan","read-only","on-request","workspace-write","deny")
                .contains(value)) throw new IllegalArgumentException("unsupported approval mode");
        return value;
    }
    private static int modeRank(String value) {
        return switch (value) {
            case "workspace-write" -> 3;
            case "on-request" -> 2;
            case "read-only" -> 1;
            case "plan", "deny" -> 0;
            default -> -1;
        };
    }
    private static String verificationMode(String value) {
        if (!java.util.Set.of("off", "auto", "configured").contains(value)) {
            throw new IllegalArgumentException("unsupported verification mode");
        }
        return value;
    }
    private void require(Status expected){if(status!=expected)throw new IllegalStateException("session is "+status);}
    private static String id(String value,String field){if(value==null||!SAFE_ID.matcher(value).matches())throw new IllegalArgumentException("invalid "+field);return value;}
    private static String bounded(String value,String field,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("invalid "+field);return value;}
    private static String fingerprint(String value) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid providerConfigurationFingerprint");
        }
        return value;
    }

    public record Snapshot(String sessionId,String workspaceLabel,String provider,String model,
            String providerSource,String approvalMode,String verificationMode,
            String verificationPreset,String verificationSource,
            TerminalMode terminalMode,Status status,String currentTaskId,
            String currentRunId,String lastTaskId,long contextGeneration,
            CancellationState cancellationState,SessionBinding binding,
            Instant startedAt,Instant endedAt,
            String providerConfigurationFingerprint) { }
}
