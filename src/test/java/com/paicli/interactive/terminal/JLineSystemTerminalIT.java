package com.paicli.interactive.terminal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JLineSystemTerminalIT {
    @Test void currentEnvironmentCreatesSystemOrDumbFallbackReader() throws Exception {
        try(var context=new InteractiveTerminalFactory().createSystem()){
            assertNotNull(context.terminal());assertNotNull(context.reader());
        }
    }
}
