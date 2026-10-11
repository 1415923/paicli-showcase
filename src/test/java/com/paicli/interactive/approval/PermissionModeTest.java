package com.paicli.interactive.approval;

import com.paicli.config.Config;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.safety.ApprovalDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PermissionModeTest {
    @TempDir Path temp;

    @Test void modesHaveInteractiveWriteSemantics() {
        for (String denied : new String[]{Config.MODE_PLAN, Config.MODE_READ_ONLY, Config.MODE_DENY}) {
            InteractiveApprovalPolicy policy = new InteractiveApprovalPolicy(() -> denied, "task");
            assertEquals(ApprovalDecision.Decision.DENY,
                    policy.evaluate("write_file", true).decision());
            assertTrue(policy.evaluate("read_file", false).isAllowed() || denied.equals(Config.MODE_DENY));
        }
        assertEquals(ApprovalDecision.Decision.NEEDS_APPROVAL,
                new InteractiveApprovalPolicy(() -> Config.MODE_ON_REQUEST, "task")
                        .evaluate("write_file", false).decision());
        assertTrue(new InteractiveApprovalPolicy(() -> Config.MODE_WORKSPACE_WRITE, "task")
                .evaluate("write_file", true).isAllowed());
        assertFalse(new InteractiveApprovalPolicy(() -> Config.MODE_WORKSPACE_WRITE, "task")
                .evaluate("run_command", false).isAllowed());
    }

    @Test void activeTaskMayLowerButCannotRaisePermission() {
        InteractiveSession session = new InteractiveSession("session-1", temp,
                "ollama", "model", Config.MODE_WORKSPACE_WRITE,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        session.startTask("task-1");
        assertEquals(Config.MODE_ON_REQUEST,
                session.changeApprovalMode(Config.MODE_ON_REQUEST));
        assertEquals(Config.MODE_READ_ONLY,
                session.changeApprovalMode(Config.MODE_READ_ONLY));
        assertThrows(IllegalStateException.class,
                () -> session.changeApprovalMode(Config.MODE_WORKSPACE_WRITE));
    }
}
