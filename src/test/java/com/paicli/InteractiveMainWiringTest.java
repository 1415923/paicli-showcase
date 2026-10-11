package com.paicli;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveMainWiringTest {
    @Test void helpAdvertisesInteractiveWithoutChangingVersion() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            assertEquals(0, PaiCLI.run(new String[]{"--help"}));
        } finally {
            System.setOut(originalOut);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("paicli                            Enter interactive mode"));
        assertTrue(output.contains("PaiCLI 1.2.0"));
        assertFalse(output.contains("Interactive Preview"));
    }
}
