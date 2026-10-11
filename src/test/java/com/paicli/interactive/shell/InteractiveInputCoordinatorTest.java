package com.paicli.interactive.shell;

import com.paicli.config.Config;
import com.paicli.interactive.approval.ApprovalInteractionResult;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.diff.PreWriteDiff;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.safety.ApprovalDecision;
import com.paicli.interactive.steering.SteeringConfirmationRequest;
import com.paicli.interactive.followup.FollowUpConfirmationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveInputCoordinatorTest {
    @TempDir Path temp;

    @Test void approvalArrivingDuringIdleReadCannotTakeAlreadyEnteredInput() throws Exception {
        AtomicReference<InteractiveApprovalRequest> pending = new AtomicReference<>();
        FakeReader reader = new FakeReader(() -> pending.set(request()), "n");
        InteractiveInputCoordinator coordinator = new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(port(pending));
        InteractiveSession session = session();

        InteractiveInputCoordinator.InputFrame frame = coordinator.read(
                session, new InteractivePromptRenderer());

        assertEquals(InteractiveInputCoordinator.State.IDLE, frame.state());
        assertEquals("n", frame.value());
        assertNull(frame.approvalRequestId());
        assertEquals(1, reader.reads);
    }

    @Disabled("Deferred v1.3 active-composer approval refresh contract")
    @Test void approvalArrivalRefreshesToToolPromptWithoutCreatingAnotherReader()
            throws Exception {
        AtomicReference<InteractiveApprovalRequest> pending = new AtomicReference<>();
        AtomicReference<Runnable> refresh = new AtomicReference<>(() -> { });
        TaskSubmissionPort runtime = new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest request) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public Optional<InteractiveApprovalRequest> pendingApproval() {
                return Optional.ofNullable(pending.get());
            }
            public void onPromptRefresh(Runnable listener) { refresh.set(listener); }
        };
        FakeReader reader = new FakeReader(() -> {
            pending.set(request());
            refresh.get().run();
        }, "n");
        InteractiveInputCoordinator coordinator = new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(runtime);
        InteractiveSession session = session();
        session.startTask("task-1", "run-1");

        InteractiveInputCoordinator.InputFrame frame = coordinator.read(
                session, new InteractivePromptRenderer());

        assertEquals(InteractiveInputCoordinator.State.TASK_RUNNING, frame.state());
        assertNull(frame.approvalRequestId());
        assertEquals(1, reader.reads);
        assertEquals(1, reader.refreshes.size());
        assertTrue(reader.refreshes.getFirst().startsWith("approval [edit A.java]>"));
        assertTrue(reader.refreshes.getFirst().contains("Apply?"));
        assertFalse(reader.refreshes.getFirst().contains("running: IDLE"));
    }

    @Disabled("Replaced by v1.2 foreground approval ownership tests")
    @Test void approvalOwnedReadStaysBoundToExpiredOriginalApproval()throws Exception{
        AtomicReference<InteractiveApprovalRequest> pending=new AtomicReference<>(request());
        FakeReader reader=new FakeReader(()->pending.set(null),"yes");
        InteractiveInputCoordinator coordinator=new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(port(pending));
        InteractiveSession session=session();session.startTask("task-1","run-1");
        var frame=coordinator.read(session,new InteractivePromptRenderer());
        assertEquals(InteractiveInputCoordinator.State.APPROVAL_PENDING,frame.state());
        assertEquals("approval-1",frame.approvalRequestId());
        assertEquals("yes",frame.value());
        assertNull(pending.get());
    }

    @Test void aSecondReaderThreadIsRejected() throws Exception {
        InteractiveInputCoordinator coordinator = new InteractiveInputCoordinator(
                new FakeReader(null, "/status"));
        coordinator.bindRuntime(port(new AtomicReference<>()));
        coordinator.read(session(), new InteractivePromptRenderer());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { coordinator.readSelector("select: "); }
            catch (Throwable error) { failure.set(error); }
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Disabled("Deferred v1.3 running-task Steering confirmation")
    @Test void steeringConfirmationHasItsOwnInputOwnerState()throws Exception{
        TaskSubmissionPort runtime=new TaskSubmissionPort(){
            public TaskSubmissionResult submit(InteractiveTaskRequest request){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<SteeringConfirmationRequest> pendingSteeringConfirmation(){
                return Optional.of(new SteeringConfirmationRequest(
                        "str_123456789abc4def8abc123456789abc","tsk_123456789abc4def8abc123456789abc","unknown"));
            }
        };
        FakeReader reader=new FakeReader(null,"n");InteractiveInputCoordinator coordinator=new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(runtime);InteractiveSession session=session();session.startTask("task-1","run-1");
        var frame=coordinator.read(session,new InteractivePromptRenderer());
        assertEquals(InteractiveInputCoordinator.State.STEERING_CONFIRMATION_PENDING,frame.state());
        assertTrue(reader.prompts.getFirst().startsWith("steering confirmation"));
        assertEquals("str_123456789abc4def8abc123456789abc",frame.approvalRequestId());
    }

    @Disabled("Deferred v1.3 running-task Steering/approval arbitration")
    @Test void steeringConfirmationInputIsNotReusedWhenApprovalAppearsDuringRead()throws Exception{
        AtomicReference<InteractiveApprovalRequest> approval=new AtomicReference<>();
        SteeringConfirmationRequest steering=new SteeringConfirmationRequest(
                "str_123456789abc4def8abc123456789abc","tsk_123456789abc4def8abc123456789abc","unknown");
        TaskSubmissionPort runtime=new TaskSubmissionPort(){
            public TaskSubmissionResult submit(InteractiveTaskRequest request){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<SteeringConfirmationRequest> pendingSteeringConfirmation(){return Optional.of(steering);}
            public Optional<InteractiveApprovalRequest> pendingApproval(){return Optional.ofNullable(approval.get());}
        };
        FakeReader reader=new FakeReader(()->approval.set(request()),"n");
        InteractiveInputCoordinator coordinator=new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(runtime);InteractiveSession session=session();session.startTask("task-1","run-1");
        var frame=coordinator.read(session,new InteractivePromptRenderer());
        assertEquals(InteractiveInputCoordinator.State.STEERING_CONFIRMATION_PENDING,frame.state());
        assertEquals(steering.steeringId(),frame.approvalRequestId());
    }

    @Test void followUpIntentAndRiskConfirmationFreezeIdentityKindAndVersionAtReadStart()throws Exception{
        AtomicReference<FollowUpConfirmationRequest> pending=new AtomicReference<>(new FollowUpConfirmationRequest(
                "cap_123456789abc4def8abc123456789abc","INTENT",4,"tsk_123456789abc4def8abc123456789abc","intent preview"));
        TaskSubmissionPort runtime=new TaskSubmissionPort(){
            public TaskSubmissionResult submit(InteractiveTaskRequest request){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<FollowUpConfirmationRequest> pendingFollowUpConfirmation(){return Optional.ofNullable(pending.get());}
        };
        FakeReader reader=new FakeReader(()->pending.set(new FollowUpConfirmationRequest(
                "cap_223456789abc4def8abc123456789abc","RISK",7,"tsk_123456789abc4def8abc123456789abc","risk preview")),"c");
        InteractiveInputCoordinator coordinator=new InteractiveInputCoordinator(reader);coordinator.bindRuntime(runtime);
        var frame=coordinator.read(session(),new InteractivePromptRenderer());
        assertEquals(InteractiveInputCoordinator.State.FOLLOW_UP_CONFIRMATION_PENDING,frame.state());
        assertEquals("cap_123456789abc4def8abc123456789abc",frame.approvalRequestId());
        assertEquals("INTENT",frame.confirmationKind());assertEquals(4,frame.confirmationVersion());
    }

    @Test void terminalInputFreezesHeadAndVersionBeforeBlockingRead()throws Exception{
        AtomicReference<String> token=new AtomicReference<>("tsk_123456789abc4def8abc123456789abc|4");
        TaskSubmissionPort runtime=new TaskSubmissionPort(){
            public TaskSubmissionResult submit(InteractiveTaskRequest request){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<String> currentFollowUpHeadToken(){return Optional.ofNullable(token.get());}
        };
        FakeReader reader=new FakeReader(()->token.set("tsk_223456789abc4def8abc123456789abc|5"),"continue");
        InteractiveInputCoordinator coordinator=new InteractiveInputCoordinator(reader);coordinator.bindRuntime(runtime);
        var frame=coordinator.read(session(),new InteractivePromptRenderer());
        assertEquals("tsk_123456789abc4def8abc123456789abc|4",frame.followUpHeadToken());
    }

    @Disabled("Deferred v1.3 retained composer terminal handoff")
    @Test void terminalControlHandoffRefreshesIdlePromptWithoutSecondReader() throws Exception {
        FakeReader reader = new FakeReader(null, "waiting input");
        AtomicReference<Runnable> terminal = new AtomicReference<>();
        TaskSubmissionPort runtime = new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest request) {
                return TaskSubmissionResult.accepted("accepted");
            }
            public void onTerminal(Runnable listener) { terminal.set(listener); }
            public String currentPhase() { return "EXECUTING"; }
        };
        InteractiveInputCoordinator coordinator = new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(runtime);
        InteractiveSession session = session();
        session.startTask("task-1", "run-1");
        coordinator.read(session, new InteractivePromptRenderer());
        assertTrue(reader.prompts.getFirst().contains("running: EXECUTING"));

        session.finishTask("task-1");
        terminal.get().run();

        assertEquals(InteractiveInputCoordinator.State.IDLE, coordinator.state());
        assertEquals(java.util.List.of("paicli> "), reader.refreshes);
        assertEquals(1, reader.reads);
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("session-1", temp,
                Config.PROVIDER_MOCK, "model", Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        return session;
    }

    private static TaskSubmissionPort port(
            AtomicReference<InteractiveApprovalRequest> pending) {
        return new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest request) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public Optional<InteractiveApprovalRequest> pendingApproval() {
                return Optional.ofNullable(pending.get());
            }
            public ApprovalInteractionResult respondApproval(String id, String input) {
                pending.set(null);
                return new ApprovalInteractionResult(true, false, java.util.List.of());
            }
        };
    }

    private static InteractiveApprovalRequest request() {
        return new InteractiveApprovalRequest("approval-1", "session-1", "task-1",
                "run-1", 1, "write_file", "src/A.java",
                ApprovalDecision.RiskLevel.HIGH, Config.MODE_ON_REQUEST,
                new PreWriteDiff("src/A.java", "a".repeat(64), "b".repeat(64),
                        1, 1, java.util.List.of("-old", "+new"),
                        false, false, true),
                Instant.now().plusSeconds(30));
    }

    private static final class FakeReader implements ShellInputReader {
        private final Runnable beforeRead;
        private final Queue<String> values = new ArrayDeque<>();
        private int reads;
        private final java.util.List<String> prompts = new java.util.ArrayList<>();
        private final java.util.List<String> refreshes = new java.util.ArrayList<>();
        FakeReader(Runnable beforeRead, String... values) {
            this.beforeRead = beforeRead;
            java.util.Collections.addAll(this.values, values);
        }
        public String readLine(String prompt) {
            reads++;
            prompts.add(prompt);
            if (beforeRead != null) beforeRead.run();
            return values.poll();
        }
        public void runtimeStateChanged(String prompt) { refreshes.add(prompt); }
        public void close() { }
    }
}
