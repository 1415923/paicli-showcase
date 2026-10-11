package com.paicli.interactive.completion;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

public final class InteractiveCompleter implements Completer {
    private final SlashCommandCompleter slash;
    private final WorkspaceCompleter workspace;

    public InteractiveCompleter(SlashCommandCompleter slash, WorkspaceCompleter workspace) {
        this.slash = slash;
        this.workspace = workspace;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.line().stripLeading().startsWith("/")) slash.complete(reader, line, candidates);
        else workspace.complete(reader, line, candidates);
    }
}
