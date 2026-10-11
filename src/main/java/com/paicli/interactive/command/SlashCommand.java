package com.paicli.interactive.command;

public enum SlashCommand {
    HELP, STATUS, PROVIDER, MODEL, MODE, PLAN, DIFF, TASKS, SESSIONS, RESUME,
    NEW, CONTINUE, TASK, REWIND, REPORT, COMPACT, VERIFY, VERBOSE, DOCTOR, HISTORY,
    CLEAR, MCP, SKILLS, EXIT;

    public String token(){return "/"+name().toLowerCase();}
}
