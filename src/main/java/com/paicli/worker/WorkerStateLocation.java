package com.paicli.worker;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Pure path derivation for Worker-owned state and trace locations. */
public final class WorkerStateLocation {
    private static final Pattern SAFE_WORKER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private final Path managedRoot;
    private final Path assignedWorkspace;

    public WorkerStateLocation(Path managedRoot, Path assignedWorkspace) {
        this.managedRoot = Objects.requireNonNull(managedRoot, "managedRoot").toAbsolutePath().normalize();
        this.assignedWorkspace = Objects.requireNonNull(assignedWorkspace, "assignedWorkspace")
                .toAbsolutePath().normalize();
        if (this.managedRoot.startsWith(this.assignedWorkspace)
                || this.assignedWorkspace.startsWith(this.managedRoot.resolve("state"))
                || this.assignedWorkspace.startsWith(this.managedRoot.resolve("traces")))
            throw new IllegalArgumentException("Worker state root must be outside the assigned workspace");
    }

    public Location locate(String workerRunId) {
        if (workerRunId == null || !SAFE_WORKER_ID.matcher(workerRunId).matches()
                || workerRunId.equals(".") || workerRunId.equals(".."))
            throw new IllegalArgumentException("workerRunId must be a safe relative identifier");
        Path stateDirectory = managedRoot.resolve("state").resolve(workerRunId).normalize();
        Path traceDirectory = managedRoot.resolve("traces").resolve(workerRunId).normalize();
        if (!stateDirectory.startsWith(managedRoot.resolve("state"))
                || !traceDirectory.startsWith(managedRoot.resolve("traces"))
                || stateDirectory.startsWith(assignedWorkspace)
                || traceDirectory.startsWith(assignedWorkspace))
            throw new IllegalArgumentException("Derived worker state path escapes its managed root");
        return new Location(stateDirectory.resolve("paicli.db"), traceDirectory);
    }

    public record Location(Path stateDatabase, Path traceDirectory) { }
}
