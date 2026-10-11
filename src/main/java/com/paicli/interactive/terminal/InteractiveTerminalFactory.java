package com.paicli.interactive.terminal;

import com.paicli.interactive.session.InteractiveSession.TerminalMode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InteractiveTerminalFactory {
    private final SystemTerminalOpener systemOpener;

    public InteractiveTerminalFactory() {
        this(() -> TerminalBuilder.builder().system(true).dumb(true).build());
    }

    InteractiveTerminalFactory(SystemTerminalOpener systemOpener) {
        this.systemOpener = Objects.requireNonNull(systemOpener, "systemOpener");
    }

    public TerminalContext create(InputStream input, OutputStream output, boolean forcePlain) throws IOException {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(output, "output");
        Terminal terminal = TerminalBuilder.builder().system(false).streams(input, output)
                .type(forcePlain ? Terminal.TYPE_DUMB : null).dumb(true).build();
        TerminalMode mode = forcePlain ? TerminalMode.PLAIN
                : Terminal.TYPE_DUMB.equalsIgnoreCase(terminal.getType())
                ? TerminalMode.DUMB : TerminalMode.INTERACTIVE;
        return contextFor(terminal, mode);
    }

    public TerminalContext createSystem() throws IOException {
        Terminal terminal = systemOpener.open();
        TerminalMode mode = Terminal.TYPE_DUMB.equalsIgnoreCase(terminal.getType())
                ? TerminalMode.DUMB : TerminalMode.INTERACTIVE;
        return contextFor(terminal, mode);
    }

    public TerminalContext createSystemOrPlain(InputStream input, OutputStream output) throws IOException {
        com.paicli.diagnostic.StageTrace.event(
                "TERMINAL_INIT_START", false,
                "component", "JLINE_TERMINAL",
                "system_console_present", System.console() != null);
        if (System.console() == null) {
            TerminalContext plain = create(input, output, true);
            com.paicli.diagnostic.StageTrace.event(
                    "TERMINAL_INIT_END", false,
                    "result", "PLAIN_NO_SYSTEM_CONSOLE",
                    "terminal_type", plain.terminal().getType(),
                    "terminal_mode", plain.mode());
            return plain;
        }
        try {
            TerminalContext system = createSystem();
            com.paicli.diagnostic.StageTrace.event(
                    "TERMINAL_INIT_END", false,
                    "result", "SYSTEM",
                    "terminal_type", system.terminal().getType(),
                    "terminal_mode", system.mode());
            return system;
        } catch (IOException | RuntimeException error) {
            TerminalContext fallback = create(input, output, true);
            com.paicli.diagnostic.StageTrace.event(
                    "TERMINAL_INIT_END", false,
                    "result", "PLAIN_FALLBACK",
                    "terminal_type", fallback.terminal().getType(),
                    "terminal_mode", fallback.mode(),
                    "system_error", com.paicli.diagnostic.StageTrace.safeException(error));
            return fallback;
        }
    }

    private static TerminalContext contextFor(Terminal terminal, TerminalMode mode) throws IOException {
        try {
            TerminalAttributesGuard attributes = mode == TerminalMode.INTERACTIVE
                    ? TerminalAttributesGuard.acquire(terminal) : null;
            return new TerminalContext(terminal, mode, attributes);
        } catch (RuntimeException | Error failure) {
            try {
                terminal.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @FunctionalInterface interface SystemTerminalOpener { Terminal open() throws IOException; }

    public static final class TerminalContext implements AutoCloseable {
        private final Terminal terminal;
        private final TerminalMode mode;
        private final TerminalAttributesGuard attributes;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private LineReader compatibilityReader;

        private TerminalContext(Terminal terminal, TerminalMode mode,
                TerminalAttributesGuard attributes) {
            this.terminal = Objects.requireNonNull(terminal, "terminal");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.attributes = attributes;
        }

        public Terminal terminal() { return terminal; }
        public TerminalMode mode() { return mode; }

        /**
         * Compatibility accessor for bounded terminal tests. Production composition creates
         * exactly one configured LineReader and never calls this method.
         */
        public synchronized LineReader reader() {
            if (compatibilityReader == null) {
                compatibilityReader = LineReaderBuilder.builder().terminal(terminal).build();
            }
            return compatibilityReader;
        }

        public boolean plain() { return mode != TerminalMode.INTERACTIVE; }
        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            Throwable failure = null;
            try {
                if (attributes != null) attributes.close();
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            try {
                terminal.close();
            } catch (IOException | RuntimeException | Error error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure instanceof IOException error) throw error;
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }
    }
}
