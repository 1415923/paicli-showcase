package com.paicli.interactive.command;

public record SlashCommandSpec(SlashCommand command, String syntax, boolean allowedWhileTaskRunning,
        boolean mutatesSessionState, boolean selectorRequired, int minArguments, int maxArguments,
        String outputContract, String errorContract) {
    public static final int MAX_ARGUMENTS = 64;

    public SlashCommandSpec {
        if(minArguments<0||maxArguments<minArguments||maxArguments>MAX_ARGUMENTS)
            throw new IllegalArgumentException("invalid arity");
        if(syntax==null||syntax.isBlank()||outputContract==null||errorContract==null)throw new IllegalArgumentException("incomplete command spec");
    }
}
