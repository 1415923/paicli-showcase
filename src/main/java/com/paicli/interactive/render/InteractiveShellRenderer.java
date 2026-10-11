package com.paicli.interactive.render;

import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.TaskSubmissionResult;

import java.util.List;

public interface InteractiveShellRenderer {
    void startup(String applicationVersion, InteractiveSession.Snapshot session);
    void lines(List<String> lines);
    void taskResult(TaskSubmissionResult result);
    default void beginTaskOutput() { }
    default void endTaskOutput() { }
    void inputCleared();
    void warning(String message);
    void error(String code, String message);
    default void flush() { }
    void closed();
}
