package com.paicli.interactive.terminal;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalAttributesGuard implements AutoCloseable {
    private final Terminal terminal;
    private final Attributes original;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TerminalAttributesGuard(Terminal terminal, Attributes original) {
        this.terminal = terminal;
        this.original = original;
    }

    static TerminalAttributesGuard acquire(Terminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        Attributes original = new Attributes(terminal.getAttributes());
        try {
            terminal.enterRawMode();
            Attributes interactive = new Attributes(terminal.getAttributes());
            // v1.2 keeps normal console/JVM signal delivery. JLine may use
            // non-canonical input, but PaiCLI does not suppress ISIG/processed input.
            interactive.setLocalFlag(Attributes.LocalFlag.ISIG, true);
            terminal.setAttributes(interactive);
            return new TerminalAttributesGuard(terminal, original);
        } catch (RuntimeException | Error failure) {
            try {
                terminal.setAttributes(new Attributes(original));
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            terminal.setAttributes(new Attributes(original));
        }
    }
}
