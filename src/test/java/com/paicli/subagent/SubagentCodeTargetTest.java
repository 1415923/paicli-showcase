package com.paicli.subagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubagentCodeTargetTest {

    @Test
    void validatesClassAndMethodTargetsAndBudget() {
        assertEquals(SubagentCodeTarget.TargetKind.CLASS,
                SubagentCodeTarget.forClass("com.example.App", true).kind());
        assertEquals("run",
                SubagentCodeTarget.forMethod("com.example.App", "run", false)
                        .methodName());

        assertThrows(IllegalArgumentException.class, () -> new SubagentCodeTarget(
                SubagentCodeTarget.TargetKind.METHOD, "App", null, true, 1_024));
        assertThrows(IllegalArgumentException.class, () -> new SubagentCodeTarget(
                SubagentCodeTarget.TargetKind.CLASS, "App", null, true, 100));
    }
}
