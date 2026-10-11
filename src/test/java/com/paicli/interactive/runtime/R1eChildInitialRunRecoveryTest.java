package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.runtime.FollowUpService;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class R1eChildInitialRunRecoveryTest {
    @TempDir Path workspace;
    private static final Instant AT=Instant.parse("2026-07-20T00:00:00Z");

    @Test void rejectedWorkerSubmissionReleasesDurableClaimForSameChildAndInitialRun()throws Exception{
        CanonicalId thread;CanonicalId child;CanonicalId run;
        StateStore state=new StateStore(workspace);
        var parent=new InstructionManifestRuntime(state,workspace).admitRoot(
                CanonicalId.newId(EntityFamily.TASK),"parent",10_000,config(),
                RuntimeManifest.disabled(),AT);
        terminal(parent.taskId(),parent.initialRun().id());
        var admitted=new FollowUpService(state,new InstructionManifestRuntime(state,workspace),
                config(),RuntimeManifest.disabled(),10_000).begin(parent.threadId(),
                "continue child",true,"owner","session",AT.plusSeconds(1));
        thread=parent.threadId();child=admitted.child().taskId();run=admitted.child().runId();

        InteractiveRuntimeAdapter adapter=new InteractiveRuntimeAdapter(workspace,
                (taskId,request,store,bridge)->{throw new AssertionError("root execution is unused");},
                ignored->{},new InteractiveRuntimeView(),state,new RejectingExecutor());
        InteractiveSession session=session();adapter.openSession(session);
        new SavedSessionService(state,workspace,Clock.fixed(AT.plusSeconds(2),ZoneOffset.UTC))
                .bind(session,thread.value());
        String token=state.followUps.currentHeadToken(thread).orElseThrow();
        assertEquals("CHILD_EXECUTION_START_FAILED",
                adapter.submitFollowUp("idempotent retry",true,token).code());
        assertEquals("WORKER_SUBMISSION_FAILED",text(workspace,
                "SELECT failure_reason FROM child_initial_run_launches WHERE child_task_id='"
                        +child.value()+"'"));
        adapter.close();

        try(StateStore reopened=new StateStore(workspace)){
            var pending=reopened.followUps.findRecoverableAdmittedChild(thread,AT.plusSeconds(3)).orElseThrow();
            assertEquals(child,pending.child().taskId());
            assertEquals(run,pending.child().runId());
        }
    }

    private void terminal(CanonicalId task,CanonicalId run)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                Statement statement=manager.getConnection().createStatement()){
            statement.executeUpdate("UPDATE task_identities SET status='COMPLETED',terminal_at='"
                    +AT+"' WHERE task_id='"+task.value()+"'");
            statement.executeUpdate("UPDATE durable_runs SET terminal_status='COMPLETED' WHERE run_id='"
                    +run.value()+"'");
            statement.executeUpdate("UPDATE tasks SET status='DONE',completed_at='"+AT+"' WHERE task_id='"
                    +task.value()+"'");
            statement.executeUpdate("UPDATE coding_runs SET phase='COMPLETED',completed_at='"+AT
                    +"' WHERE run_id='"+run.value()+"'");
        }
    }

    private Config config(){return new Config(workspace.toString(),30,50_000,200_000,
            Config.MODE_WORKSPACE_WRITE,10_000,Config.PROVIDER_MOCK,"model","","","system",
            10,"",120,50_000,1,false,false,false);}

    private InteractiveSession session(){
        InteractiveSession session=new InteractiveSession("session",workspace,Config.PROVIDER_MOCK,
                "model",Config.MODE_WORKSPACE_WRITE,InteractiveSession.TerminalMode.PLAIN,AT);
        session.start();return session;
    }

    private static String text(Path workspace,String sql)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                Statement statement=manager.getConnection().createStatement();
                var row=statement.executeQuery(sql)){assertTrue(row.next());return row.getString(1);}
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown(){shutdown=true;}
        @Override public List<Runnable> shutdownNow(){shutdown=true;return List.of();}
        @Override public boolean isShutdown(){return shutdown;}
        @Override public boolean isTerminated(){return shutdown;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return shutdown;}
        @Override public void execute(Runnable command){throw new RejectedExecutionException("injected");}
    }
}
