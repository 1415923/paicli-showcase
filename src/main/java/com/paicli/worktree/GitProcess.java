package com.paicli.worktree;

import java.nio.file.Path;

/** Narrow operation-level process boundary used by the worktree service. */
interface GitProcess {
    HardenedGitProcess.Result execute(Path workingDirectory,
                                      HardenedGitProcess.Operation operation,
                                      String... values);
}
