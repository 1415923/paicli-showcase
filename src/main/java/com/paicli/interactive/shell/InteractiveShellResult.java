package com.paicli.interactive.shell;

import com.paicli.interactive.session.InteractiveSession;

public record InteractiveShellResult(
        InteractiveSession.Status status, int submittedTasks, int rendererFailures) { }