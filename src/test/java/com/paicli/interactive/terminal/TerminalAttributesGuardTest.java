package com.paicli.interactive.terminal;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerminalAttributesGuardTest {
    @Test
    void interactiveFactoryOwnsGuardUntilContextClose() throws Exception {
        FakeTerminal terminal = new FakeTerminal(originalAttributes());
        Terminal proxy = terminal.proxy();
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory(() -> proxy);

        InteractiveTerminalFactory.TerminalContext context = factory.createSystem();
        assertTrue(terminal.current().getLocalFlag(Attributes.LocalFlag.ISIG));
        assertFalse(terminal.current().getLocalFlag(Attributes.LocalFlag.ICANON));
        assertFalse(terminal.current().getLocalFlag(Attributes.LocalFlag.ECHO));

        context.close();
        context.close();

        assertAttributesEqual(originalAttributes(), terminal.current());
        assertEquals(1, terminal.closeCount());
    }

    @Test
    void controlCIsNotTranslatedToApplicationSendBreak() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/paicli/interactive/shell/JLineShellInputReader.java"));
        assertFalse(source.contains("LineReader.SEND_BREAK"));
        assertFalse(source.contains("Terminal.Signal.INT"));
    }

    @Test
    void savesFullAttributesAndKeepsProcessedSignalInputDuringSession() {
        FakeTerminal terminal = new FakeTerminal(originalAttributes());

        TerminalAttributesGuard guard = TerminalAttributesGuard.acquire(terminal.proxy());

        Attributes active = terminal.current();
        assertTrue(active.getLocalFlag(Attributes.LocalFlag.ISIG));
        assertFalse(active.getLocalFlag(Attributes.LocalFlag.ICANON));
        assertFalse(active.getLocalFlag(Attributes.LocalFlag.ECHO));
        assertEquals(0, active.getControlChar(Attributes.ControlChar.VMIN));
        assertEquals(1, active.getControlChar(Attributes.ControlChar.VTIME));
        assertEquals(2, terminal.setHistory().size());

        guard.close();
        assertAttributesEqual(originalAttributes(), terminal.current());
    }

    @Test
    void closeRestoresOriginalAttributesExactlyOnce() {
        FakeTerminal terminal = new FakeTerminal(originalAttributes());
        TerminalAttributesGuard guard = TerminalAttributesGuard.acquire(terminal.proxy());

        guard.close();
        guard.close();

        assertEquals(3, terminal.setHistory().size());
        assertAttributesEqual(originalAttributes(), terminal.current());
    }

    @Test
    void startupFailureRestoresOriginalAttributesBeforePropagating() {
        FakeTerminal terminal = new FakeTerminal(originalAttributes());
        terminal.failSetNumber(2);
        Terminal proxy = terminal.proxy();
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory(() -> proxy);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                factory::createSystem);

        assertEquals("configured set failure", error.getMessage());
        assertEquals(3, terminal.setHistory().size());
        assertAttributesEqual(originalAttributes(), terminal.current());
        assertEquals(1, terminal.closeCount());
    }

    @Test
    void unexpectedSessionFailureStillRestoresOriginalAttributes() {
        FakeTerminal terminal = new FakeTerminal(originalAttributes());

        assertThrows(IllegalArgumentException.class, () -> {
            try (TerminalAttributesGuard ignored =
                         TerminalAttributesGuard.acquire(terminal.proxy())) {
                throw new IllegalArgumentException("session failure");
            }
        });

        assertAttributesEqual(originalAttributes(), terminal.current());
    }

    private static Attributes originalAttributes() {
        Attributes attributes = new Attributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, true);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, true);
        attributes.setLocalFlag(Attributes.LocalFlag.IEXTEN, true);
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, true);
        attributes.setInputFlag(Attributes.InputFlag.IXON, true);
        attributes.setOutputFlag(Attributes.OutputFlag.OPOST, true);
        attributes.setControlFlag(Attributes.ControlFlag.CREAD, true);
        attributes.setControlChar(Attributes.ControlChar.VINTR, 3);
        attributes.setControlChar(Attributes.ControlChar.VMIN, 1);
        attributes.setControlChar(Attributes.ControlChar.VTIME, 0);
        return attributes;
    }

    private static void assertAttributesEqual(Attributes expected, Attributes actual) {
        assertEquals(expected.getInputFlags(), actual.getInputFlags());
        assertEquals(expected.getOutputFlags(), actual.getOutputFlags());
        assertEquals(expected.getControlFlags(), actual.getControlFlags());
        assertEquals(expected.getLocalFlags(), actual.getLocalFlags());
        assertEquals(expected.getControlChars(), actual.getControlChars());
    }

    private static final class FakeTerminal {
        private Attributes current;
        private final List<Attributes> setHistory = new ArrayList<>();
        private int failSetNumber = -1;
        private int closeCount;

        private FakeTerminal(Attributes original) {
            current = new Attributes(original);
        }

        Terminal proxy() {
            return (Terminal) Proxy.newProxyInstance(
                    Terminal.class.getClassLoader(),
                    new Class<?>[]{Terminal.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getAttributes" -> new Attributes(current);
                        case "setAttributes" -> {
                            Attributes next = new Attributes((Attributes) arguments[0]);
                            setHistory.add(next);
                            if (setHistory.size() == failSetNumber) {
                                throw new IllegalStateException("configured set failure");
                            }
                            current = next;
                            yield null;
                        }
                        case "enterRawMode" -> enterRawMode();
                        case "getType" -> "windows-vtp";
                        case "close" -> {
                            closeCount++;
                            yield null;
                        }
                        case "toString" -> "FakeTerminal";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Attributes enterRawMode() {
            Attributes saved = new Attributes(current);
            Attributes raw = new Attributes(current);
            raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            raw.setLocalFlag(Attributes.LocalFlag.IEXTEN, false);
            raw.setInputFlag(Attributes.InputFlag.IXON, false);
            raw.setInputFlag(Attributes.InputFlag.ICRNL, false);
            raw.setInputFlag(Attributes.InputFlag.INLCR, false);
            raw.setControlChar(Attributes.ControlChar.VMIN, 0);
            raw.setControlChar(Attributes.ControlChar.VTIME, 1);
            setHistory.add(new Attributes(raw));
            current = raw;
            return saved;
        }

        Attributes current() {
            return new Attributes(current);
        }

        List<Attributes> setHistory() {
            return setHistory;
        }

        void failSetNumber(int number) {
            failSetNumber = number;
        }

        int closeCount() {
            return closeCount;
        }
    }
}
