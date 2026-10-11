package com.paicli.demo.cli;

import com.paicli.PaiCLI;
import com.paicli.demo.workflow.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoCliWiringTest {
    @Test void productionFactoryCannotSelectTheR2Stub() {
        assertInstanceOf(CoreDemoFullWorkflow.class,CoreDemoWorkflowFactory.production());
    }

    @Test void helpAdvertisesOnlyTheFixedHeadlessCommandAndKeepsVersion() throws Exception {
        PrintStream original=System.out;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
            PaiCLI.main(new String[]{"--help"});
        } finally { System.setOut(original); }
        String help=bytes.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("paicli demo core --headless"));
        assertTrue(help.contains("PaiCLI 1.2.0"));
        assertFalse(help.contains("--demo-root"));
        assertFalse(help.contains("--approve-all"));
    }
}
