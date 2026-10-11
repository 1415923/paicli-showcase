package com.paicli.interactive.view;

public record FollowUpView(String capsuleId, String candidateState,
        boolean confirmationRequired, boolean childInitialRunPending,
        String childTaskId, String childRunId) { }
