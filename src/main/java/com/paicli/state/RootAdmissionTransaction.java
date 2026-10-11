package com.paicli.state;

import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aDurableModels.TaskContextPointer;
import com.paicli.state.R1aDurableModels.ThreadHead;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.StructuredPlanModels.PlanApplicability;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Complete durable authority created by one Root admission database transaction. */
public final class RootAdmissionTransaction {
    private RootAdmissionTransaction() { }

    public record Request(WorkThread thread, DurableTask task, String manifestId,
                          InstructionManifestCandidate candidate,
                          List<ProtectedInstructionArtifact> artifacts,
                          DurableRun initialRun, TaskContextGeneration generation,
                          TaskContextPointer pointer, RuntimeTask runtimeTask,
                          RuntimeRun runtimeRun, PlanApplicability planApplicability,
                          Instant committedAt) {
        public Request {
            Objects.requireNonNull(thread);
            Objects.requireNonNull(task);
            Objects.requireNonNull(candidate);
            artifacts = List.copyOf(artifacts);
            Objects.requireNonNull(initialRun);
            Objects.requireNonNull(generation);
            Objects.requireNonNull(pointer);
            Objects.requireNonNull(runtimeTask);
            Objects.requireNonNull(runtimeRun);
            Objects.requireNonNull(planApplicability);
            Objects.requireNonNull(committedAt);
            if (manifestId == null || manifestId.isBlank()) {
                throw new IllegalArgumentException("Manifest ID is required");
            }
        }
    }

    /** Legacy runtime Task authority committed with the canonical Root graph. */
    public record RuntimeTask(String taskId, String goal, String provider, String model,
                              String approvalMode, int maxIterations, String status) {
        public RuntimeTask {
            if (taskId == null || taskId.isBlank() || goal == null || goal.isBlank()
                    || maxIterations < 1 || !"RUNNING".equals(status)) {
                throw new IllegalArgumentException("Complete running runtime Task is required");
            }
        }
    }

    /** Legacy coding runtime authority sharing the canonical Initial Run identity. */
    public record RuntimeRun(String runId, String taskId, String runKind, String phase,
                             String verificationCommand, int maxRepairAttempts,
                             int startedIteration, String skillsManifestHash,
                             String hooksManifestHash) {
        public RuntimeRun {
            if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()
                    || !"INITIAL".equals(runKind)
                    || !("PLANNING".equals(phase)||"EXECUTING".equals(phase))
                    || maxRepairAttempts < 1 || startedIteration < 0) {
                throw new IllegalArgumentException("Complete Initial runtime Run is required");
            }
        }
    }

    public sealed interface Result permits Success, Rejected { }

    public record Success(WorkThread thread, DurableTask task,
                          InstructionManifest manifest, DurableRun initialRun,
                          TaskContextGeneration generation, TaskContextPointer pointer,
                          ThreadHead threadHead, RuntimeTask runtimeTask,
                          RuntimeRun runtimeRun) implements Result { }

    public record Rejected(Reason reason) implements Result {
        public Rejected { Objects.requireNonNull(reason); }
    }

    public enum Reason {
        ALREADY_EXISTS,
        INTEGRITY_VIOLATION,
        MANIFEST_REJECTED,
        ARTIFACT_FAILURE,
        PERSISTENCE_FAILURE
    }
}
