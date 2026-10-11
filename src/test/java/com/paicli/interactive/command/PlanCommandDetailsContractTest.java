package com.paicli.interactive.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanCommandDetailsContractTest {
    private final SlashCommandParser parser =
            new SlashCommandParser(new SlashCommandRegistry());

    @Test
    void PLAN_PLAIN_HAS_NO_ARGUMENTS_AND_DETAILS_IS_THE_ONLY_EXPANSION() {
        assertEquals(List.of(), parser.parse("/plan", false).arguments());
        assertEquals(List.of("--details"),
                parser.parse("/plan --details", false).arguments());
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("/plan details", false));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("/plan --details extra", false));
    }
}
