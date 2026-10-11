package com.paicli.interactive.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R3CompactCommandTest {
    private final SlashCommandRegistry registry=new SlashCommandRegistry();
    private final SlashCommandParser parser=new SlashCommandParser(registry);
    @Test void plainCompactAndStatusArePublicWhileCandidateMutationCommandsAreInternal(){
        assertEquals(SlashCommand.COMPACT,parser.parse("/compact",false).command());
        assertEquals("status",parser.parse("/compact status",false).arguments().getFirst());
        assertThrows(IllegalArgumentException.class,()->parser.parse("/compact preview",false));
        assertThrows(IllegalArgumentException.class,()->parser.parse(
                "/compact apply ctx_0123456789ab4def8abc0123456789ab",false));
    }
    @Test void taskRunningSurfaceStillParsesOnlyStatusAndPlainCommand(){
        assertEquals("status",parser.parse("/compact status",true).arguments().getFirst());
        assertEquals(SlashCommand.COMPACT,parser.parse("/compact",true).command());
        assertThrows(IllegalArgumentException.class,()->parser.parse("/compact preview",true));
        assertThrows(IllegalArgumentException.class,()->parser.parse(
                "/compact apply ctx_0123456789ab4def8abc0123456789ab",true));
    }
}
