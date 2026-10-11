package com.paicli.agent;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.context.ContextModels.OperationKind;

import java.util.List;
import java.util.Objects;

/** Input for one runner-managed AgentLoop execution segment. */
public record AgentSegmentRequest(
        String goal,
        List<AgentMessage> conversation,
        String additionalPrompt,
        int startIteration,
        int maxSegmentIterations,
        PlanExecutionContext planExecution,
        OperationKind providerOperation,
        boolean finalResponseOnly
) {
    public AgentSegmentRequest(String goal, List<AgentMessage> conversation,
            String additionalPrompt, int startIteration, int maxSegmentIterations) {
        this(goal, conversation, additionalPrompt, startIteration, maxSegmentIterations, null,
                OperationKind.CHAT, false);
    }

    public AgentSegmentRequest(String goal,List<AgentMessage> conversation,String additionalPrompt,
            int startIteration,int maxSegmentIterations,PlanExecutionContext planExecution){
        this(goal,conversation,additionalPrompt,startIteration,maxSegmentIterations,planExecution,
                OperationKind.CHAT, false);
    }

    public AgentSegmentRequest(String goal,List<AgentMessage> conversation,String additionalPrompt,
            int startIteration,int maxSegmentIterations,PlanExecutionContext planExecution,
            OperationKind providerOperation) {
        this(goal,conversation,additionalPrompt,startIteration,maxSegmentIterations,planExecution,
                providerOperation,false);
    }

    public AgentSegmentRequest {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(providerOperation,"providerOperation");
        conversation = List.copyOf(Objects.requireNonNull(conversation, "conversation"));
        if (startIteration < 0) {
            throw new IllegalArgumentException("startIteration must not be negative");
        }
        if (maxSegmentIterations <= 0) {
            throw new IllegalArgumentException("maxSegmentIterations must be positive");
        }
        if ((long) startIteration + maxSegmentIterations > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Segment iteration range is too large");
        }
    }

    /** Typed R1C-B1 identity carried through the real AgentLoop segment. */
    public record PlanExecutionContext(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, CanonicalId runId, int attemptNumber) {
        public PlanExecutionContext {
            require(taskId, EntityFamily.TASK);
            require(planRevisionId, EntityFamily.PLAN);
            require(stepRevisionId, EntityFamily.PLAN_STEP);
            require(runId, EntityFamily.RUN);
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
        }

        private static void require(CanonicalId id, EntityFamily family) {
            if (id == null || id.family() != family) {
                throw new IllegalArgumentException("Expected " + family + " canonical ID");
            }
        }
    }
}
