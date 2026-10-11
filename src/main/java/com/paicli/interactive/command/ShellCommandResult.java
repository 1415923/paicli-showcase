package com.paicli.interactive.command;

import java.util.List;

public record ShellCommandResult(Action action, String code, List<String> lines) {
    public enum Action { CONTINUE, EXIT }
    public ShellCommandResult {
        if (action == null || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}") || lines == null) {
            throw new IllegalArgumentException("invalid shell command result");
        }
        lines = List.copyOf(lines);
    }
    public static ShellCommandResult continueWith(String code, List<String> lines) {
        return new ShellCommandResult(Action.CONTINUE, code, lines);
    }
}