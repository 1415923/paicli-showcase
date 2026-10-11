package com.paicli.interactive.terminal;

import com.paicli.interactive.history.InteractiveHistoryStore;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;

public final class InteractiveLineReaderFactory {
    public LineReader create(Terminal terminal, Completer completer,
            InteractiveHistoryStore historyStore) {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(new DefaultParser())
                .completer(completer)
                .highlighter(new InteractiveInputHighlighter())
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .build();
        for (String entry : historyStore.load()) reader.getHistory().add(entry);
        return reader;
    }
}
