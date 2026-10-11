package com.paicli.interactive.shell;

import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.provider.ProviderPrompt;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.TaskSubmissionPort;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** The sole owner of terminal reads for one interactive Session. */
public final class InteractiveInputCoordinator implements AutoCloseable {
    public enum State { IDLE, TASK_RUNNING, APPROVAL_PENDING, STEERING_CONFIRMATION_PENDING,
        FOLLOW_UP_CONFIRMATION_PENDING, SELECTOR_PENDING, CLOSING }

    private final ShellInputReader input;
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicLong promptGeneration = new AtomicLong();
    private volatile TaskSubmissionPort runtime = TaskSubmissionPort.runtimeNotConnected();
    private volatile State state = State.IDLE;
    private volatile InteractivePromptRenderer prompts = new InteractivePromptRenderer();
    private volatile String sessionId;
    private volatile InteractiveSession.Snapshot currentSnapshot;

    public InteractiveInputCoordinator(ShellInputReader input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public void bindRuntime(TaskSubmissionPort runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public InputFrame read(InteractiveSession session, InteractivePromptRenderer prompts)
            throws InputInterruptedException {
        requireOwner();
        this.prompts = prompts;
        InteractiveSession.Snapshot snapshot = session.snapshot();
        if (snapshot.currentTaskId() != null) {
            throw new IllegalStateException(
                    "main composer is unavailable while a foreground Task is active");
        }
        currentSnapshot = snapshot;
        sessionId = snapshot.sessionId();
        Optional<InteractiveApprovalRequest> before = Optional.empty();
        var steeringBefore=Optional.<com.paicli.interactive.steering.SteeringConfirmationRequest>empty();
        var followUpBefore=runtime.pendingFollowUpConfirmation();
        String followUpHeadBefore=runtime.currentFollowUpHeadToken().orElse(null);
        State beforeState = before.isPresent() ? State.APPROVAL_PENDING
                : steeringBefore.isPresent()?State.STEERING_CONFIRMATION_PENDING
                : followUpBefore.isPresent()?State.FOLLOW_UP_CONFIRMATION_PENDING
                : State.IDLE;
        state = beforeState;
        String prompt = before.map(prompts::approval).orElseGet(() ->steeringBefore.map(prompts::steering)
                .orElseGet(()->followUpBefore.map(prompts::followUp)
                .orElseGet(()->beforeState == State.TASK_RUNNING
                        ? prompts.running(snapshot, runtime.currentPhase())
                        : prompts.primary(snapshot))));
        long generation = promptGeneration.get();
        String value = input.readLine(prompt, identity(beforeState,
                before.map(InteractiveApprovalRequest::taskId)
                        .orElse(snapshot.currentTaskId()), generation));

        // Input ownership is frozen when the blocking read starts. Expiry,
        // invalidation, or replacement cannot repurpose this text as Steering.
        if(beforeState==State.APPROVAL_PENDING&&before.isPresent()){
            state=State.APPROVAL_PENDING;
            return new InputFrame(State.APPROVAL_PENDING,value,before.get().requestId(),false,null,-1,null);
        }

        // Input read under Steering-confirmation ownership cannot be repurposed as
        // an Approval answer merely because an Approval appeared during the read.
        if(beforeState==State.STEERING_CONFIRMATION_PENDING&&steeringBefore.isPresent()){
            state=State.STEERING_CONFIRMATION_PENDING;
            return new InputFrame(State.STEERING_CONFIRMATION_PENDING,value,
                    steeringBefore.get().steeringId(),false,null,-1,null);
        }
        if(beforeState==State.FOLLOW_UP_CONFIRMATION_PENDING&&followUpBefore.isPresent()){
            state=State.FOLLOW_UP_CONFIRMATION_PENDING;
            var request=followUpBefore.get();
            return new InputFrame(state,value,request.capsuleId(),false,request.kind(),request.candidateVersion(),null);
        }

        // A terminal handoff invalidates the old running prompt generation. The text
        // remains owned by the frozen terminal-head token and may enter R1E only if
        // that exact Parent and head version still win admission revalidation.
        if(beforeState==State.TASK_RUNNING
                &&!Objects.equals(snapshot.currentTaskId(),
                        session.snapshot().currentTaskId())){
            State after=session.snapshot().currentTaskId()==null
                    ?State.IDLE:State.TASK_RUNNING;
            state=after;
            return new InputFrame(after,value,null,true,null,-1,followUpHeadBefore);
        }

        // A still-active TASK_RUNNING owner and an IDLE owner remain frozen. Runtime
        // prompt refresh cannot capture already-entered text for another owner.
        state=beforeState;
        return new InputFrame(beforeState,value,null,false,null,-1,followUpHeadBefore);
    }

    /** Reads one approval response while the main composer remains suspended. */
    public ForegroundApprovalFrame readApproval(InteractiveSession session,
            InteractivePromptRenderer prompts, InteractiveApprovalRequest request,
            BooleanSupplier ownershipValid)
            throws InputInterruptedException {
        requireOwner();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(prompts, "prompts");
        Objects.requireNonNull(request, "request");
        InteractiveSession.Snapshot snapshot = session.snapshot();
        if (snapshot.currentTaskId() == null
                || !request.taskId().equals(snapshot.currentTaskId())) {
            throw new IllegalStateException("approval does not own the foreground Task");
        }
        this.prompts = prompts;
        currentSnapshot = snapshot;
        sessionId = snapshot.sessionId();
        state = State.APPROVAL_PENDING;
        long generation = promptGeneration.incrementAndGet();
        ShellInputReader.ForegroundReadResult result =
                input.readForegroundChoice(prompts.approval(request),
                        identity(State.APPROVAL_PENDING, request.taskId(), generation),
                        ownershipValid);
        return new ForegroundApprovalFrame(request.requestId(), result);
    }

    /** Polls Esc under the same shell-thread ownership as approval input. */
    public ShellInputReader.ForegroundReadResult pollForegroundAction(
            InteractiveSession session, String taskId,
            BooleanSupplier ownershipValid) throws InputInterruptedException {
        requireOwner();
        Objects.requireNonNull(session, "session");
        if (taskId == null || !taskId.equals(session.snapshot().currentTaskId())) {
            return ShellInputReader.ForegroundReadResult.invalidated();
        }
        state = State.TASK_RUNNING;
        long generation = promptGeneration.get();
        return input.pollForegroundAction(
                identity(State.TASK_RUNNING, taskId, generation), ownershipValid);
    }

    public String readSelector(String prompt) {
        requireOwner();
        if (runtime.pendingApproval().isPresent()) return null;
        state = State.SELECTOR_PENDING;
        try {
            return input.readLine(prompt, promptGeneration.get());
        } catch (InputInterruptedException error) {
            return null;
        } finally {
            state = State.IDLE;
        }
    }

    public ProviderPrompt selectorPrompt() { return this::readSelector; }

    public ProviderPrompt providerPrompt(){
        return new ProviderPrompt(){
            @Override public String read(String prompt){return readSelector(prompt);}
            @Override public char[] readSecret(String prompt){return InteractiveInputCoordinator.this.readSecret(prompt);}
        };
    }

    private char[] readSecret(String prompt){
        requireOwner();
        if(runtime.pendingApproval().isPresent())return null;
        state=State.SELECTOR_PENDING;
        try{return input.readSecret(prompt,identity(State.SELECTOR_PENDING,null,promptGeneration.get()));}
        catch(InputInterruptedException error){return null;}
        finally{state=State.IDLE;}
    }

    public String readContinuation(InteractiveSession session, String prompt)
            throws InputInterruptedException {
        requireOwner();
        if (session.snapshot().currentTaskId() != null || runtime.pendingApproval().isPresent()) {
            throw new IllegalStateException("multiline input interrupted by runtime state change");
        }
        state = State.IDLE;
        return input.readLine(prompt, promptGeneration.get());
    }
    public State state() { return state; }
    public boolean clearNavigationHistory() {
        requireOwner();
        return input.clearNavigationHistory();
    }

    private ShellInputReader.PromptIdentity identity(State promptState,
            String promptTaskId, long generation) {
        String currentSession = sessionId == null ? "interactive-session" : sessionId;
        return new ShellInputReader.PromptIdentity(currentSession, promptTaskId,
                generation, promptState.name());
    }

    public void closing() { state = State.CLOSING; }

    @Override public void close() {
        state = State.CLOSING;
        input.close();
    }

    private void requireOwner() {
        Thread current = Thread.currentThread();
        Thread existing = owner.get();
        if (existing == null && owner.compareAndSet(null, current)) return;
        if (owner.get() != current) {
            throw new IllegalStateException("interactive input has a different owner");
        }
    }

    public record InputFrame(State state, String value, String approvalRequestId,
            boolean terminalHandoffInvalidated,String confirmationKind,long confirmationVersion,
            String followUpHeadToken) {
        public InputFrame(State state,String value,String approvalRequestId){
            this(state,value,approvalRequestId,false,null,-1,null);
        }
        public InputFrame(State state,String value,String approvalRequestId,boolean invalidated){
            this(state,value,approvalRequestId,invalidated,null,-1,null);}
    }

    public record ForegroundApprovalFrame(String approvalRequestId,
            ShellInputReader.ForegroundReadResult input) {
        public ForegroundApprovalFrame {
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(input, "input");
        }
    }
}
