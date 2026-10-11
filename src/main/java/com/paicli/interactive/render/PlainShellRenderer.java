package com.paicli.interactive.render;

import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.TaskSubmissionResult;

import java.io.PrintWriter;
import java.util.List;

public final class PlainShellRenderer implements InteractiveShellRenderer {
    private final TerminalOutputPort output;
    public PlainShellRenderer(PrintWriter output) {
        this(TerminalOutputPort.plain(output, () -> false));
    }
    public PlainShellRenderer(TerminalOutputPort output) { this.output = output; }
    @Override public void startup(String applicationVersion, InteractiveSession.Snapshot session) {
        output.writeLines(EventLineFormatter.startupLines(applicationVersion, session));
    }
    @Override public void lines(List<String> lines) {
        output.writeLines(EventLineFormatter.shellLines(lines));
    }
    @Override public void taskResult(TaskSubmissionResult result) {
        output.writeLines(EventLineFormatter.taskResultLines(result));
    }
    @Override public void beginTaskOutput() { output.beginTaskBlock(); }
    @Override public void endTaskOutput() { output.endTaskBlock(); }
    @Override public void inputCleared() { output.writeLine("Input cleared."); }
    @Override public void warning(String message) {
        output.writeLine(EventLineFormatter.warningLine(message));
    }
    @Override public void error(String code, String message) {
        output.writeLines(EventLineFormatter.errorLines(code, message));
    }
    @Override public void flush() { output.flush(); }
    @Override public void closed() { output.writeLine("Session closed."); output.flush(); }
}
