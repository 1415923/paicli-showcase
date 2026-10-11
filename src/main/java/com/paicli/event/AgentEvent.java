package com.paicli.event;

public sealed interface AgentEvent permits AgentEvents.SessionStarted,
        AgentEvents.TaskStarted, AgentEvents.PlanCreated, AgentEvents.PlanUpdated,
        AgentEvents.PhaseChanged, AgentEvents.ModelMessage, AgentEvents.ContextLifecycle,
        AgentEvents.CodeContextSelected, AgentEvents.ToolBatchStarted,
        AgentEvents.ToolRequested,
        AgentEvents.ToolApproved, AgentEvents.ToolRejected, AgentEvents.ToolCancelled,
        AgentEvents.ToolCompleted, AgentEvents.ToolFailed, AgentEvents.ToolBatchCompleted,
        AgentEvents.FinalResponse,
        AgentEvents.ApprovalRequested,
        AgentEvents.ApprovalResolved, AgentEvents.FileRead,
        AgentEvents.DiffProposed, AgentEvents.SnapshotCreated, AgentEvents.FileWritten,
        AgentEvents.VerificationStarted, AgentEvents.VerificationFailed,
        AgentEvents.VerificationSkipped, AgentEvents.RepairStarted, AgentEvents.VerificationPassed,
        AgentEvents.TaskCompleted, AgentEvents.TaskFailed, AgentEvents.TaskCancelled,
        AgentEvents.TaskInconclusive,
        AgentEvents.CancellationRequested, AgentEvents.CancellationForced,
        AgentEvents.SessionEnded {
    AgentEventMetadata metadata();
}
