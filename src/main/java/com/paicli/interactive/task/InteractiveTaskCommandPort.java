package com.paicli.interactive.task;

import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.session.InteractiveSession;

import java.util.List;

public interface InteractiveTaskCommandPort {
    default ShellCommandResult sessions(InteractiveSession session) {
        return unavailableResult("SESSIONS_UNAVAILABLE", "Saved Sessions are unavailable.");
    }
    ShellCommandResult resume(List<String> arguments, InteractiveSession session);
    default ShellCommandResult newSession(InteractiveSession session) {
        return unavailableResult("NEW_UNAVAILABLE", "Saved Session binding is unavailable.");
    }
    default ShellCommandResult recover(List<String> arguments, InteractiveSession session) {
        return unavailableResult("TASK_RECOVERY_UNAVAILABLE", "Task Recovery is unavailable.");
    }
    default ShellCommandResult continueTask(List<String> arguments,InteractiveSession session){
        return unavailableResult("FOLLOW_UP_UNAVAILABLE","Completed-task Follow-up is unavailable.");
    }
    ShellCommandResult rewind(List<String> arguments, InteractiveSession session);

    private static ShellCommandResult unavailableResult(String code, String message) {
        return ShellCommandResult.continueWith(code, List.of(message));
    }

    static InteractiveTaskCommandPort unavailable() {
        return new InteractiveTaskCommandPort() {
            public ShellCommandResult resume(List<String> arguments, InteractiveSession session) {
                return result("RESUME_UNAVAILABLE", "Interactive resume is unavailable.");
            }
            public ShellCommandResult rewind(List<String> arguments, InteractiveSession session) {
                return result("REWIND_UNAVAILABLE", "Interactive rewind is unavailable.");
            }
            private ShellCommandResult result(String code, String message) {
                return ShellCommandResult.continueWith(code, List.of(message));
            }
        };
    }
}
