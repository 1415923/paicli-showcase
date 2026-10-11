package com.paicli.interactive.shell;

import com.paicli.config.Config;
import com.paicli.interactive.command.InteractiveDoctorPort;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.session.InteractiveSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveShellRunnerTest {
    @TempDir Path temp;

    @Test void plainPreviewNeverClaimsNaturalLanguageRuntimeSuccess() {
        StringWriter transcript = new StringWriter();
        InteractiveShellResult result = new InteractiveShellRunner().runPlainPreview(
                new InteractiveShellConfig("1.1.0", temp, Config.PROVIDER_UNCONFIGURED,
                        null, Config.MODE_ON_REQUEST, InteractiveSession.TerminalMode.PLAIN),
                new StringReader("fix the project\n/exit\n"), new PrintWriter(transcript, true),
                new InteractiveHistoryStore(temp.resolve("history")), InteractiveDoctorPort.unavailable());
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertEquals(0, result.submittedTasks());
        assertTrue(transcript.toString().contains("Interactive task runtime is unavailable"));
        assertFalse(transcript.toString().contains("[Plan]"));
        assertFalse(transcript.toString().contains("[Verify]"));
    }
}
