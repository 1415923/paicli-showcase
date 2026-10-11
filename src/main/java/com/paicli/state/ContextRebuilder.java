package com.paicli.state;

import com.paicli.config.ProjectInstructions;
import com.paicli.model.AgentMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public class ContextRebuilder {

    private static final int DEFAULT_MEMORY_CHARS = 10 * 1024;
    private static final int DEFAULT_INSTRUCTION_CHARS = 10 * 1024;

    private final StateStore stateStore;
    private final Path workspace;
    private final int maxMemoryChars;
    private final int maxInstructionChars;

    public ContextRebuilder(StateStore stateStore, Path workspace) {
        this(stateStore, workspace, DEFAULT_MEMORY_CHARS, DEFAULT_INSTRUCTION_CHARS);
    }

    public ContextRebuilder(StateStore stateStore, Path workspace,
                            int maxMemoryChars, int maxInstructionChars) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        if (maxMemoryChars < 0 || maxInstructionChars < 0) {
            throw new IllegalArgumentException("Context limits must not be negative");
        }
        this.maxMemoryChars = maxMemoryChars;
        this.maxInstructionChars = maxInstructionChars;
    }

    public ResumedContext rebuild(String taskId) throws SQLException, IOException {
        TaskStore.TaskRecord task = stateStore.tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        List<AgentMessage> conversation;
        int startIteration;
        var checkpoint = stateStore.checkpoints.loadLatest(taskId);
        if (checkpoint.isPresent()) {
            conversation = checkpoint.get().messages();
            startIteration = checkpoint.get().iteration();
        } else {
            conversation = stateStore.events.findMessages(taskId);
            // iteration 0 is the initial USER event, not an executed model iteration.
            startIteration = Math.max(0, stateStore.events.countIterations(taskId) - 1);
        }

        String projectMemoryContext = stateStore.memory.asContext(maxMemoryChars);
        ProjectInstructions projectInstructions = ProjectInstructions.load(workspace, maxInstructionChars);

        return new ResumedContext(
                task.taskId(),
                task.goal(),
                conversation,
                startIteration,
                projectMemoryContext,
                projectInstructions
        );
    }

    public record ResumedContext(
            String taskId,
            String goal,
            List<AgentMessage> conversation,
            int startIteration,
            String projectMemoryContext,
            ProjectInstructions projectInstructions
    ) {
        public ResumedContext {
            conversation = List.copyOf(conversation);
            projectMemoryContext = Objects.requireNonNull(projectMemoryContext, "projectMemoryContext");
            projectInstructions = Objects.requireNonNull(projectInstructions, "projectInstructions");
        }
    }
}
