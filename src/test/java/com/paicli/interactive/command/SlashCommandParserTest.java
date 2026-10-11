package com.paicli.interactive.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandParserTest {
    private final SlashCommandRegistry registry=new SlashCommandRegistry();
    private final SlashCommandParser parser=new SlashCommandParser(registry);

    @Test void freezesAllCoreCommands(){
        assertEquals(Set.of("/help","/status","/provider","/model","/mode","/plan","/diff","/tasks",
                "/sessions","/resume","/new","/continue","/task","/rewind","/report","/compact","/verify",
                "/verbose","/doctor","/history","/clear","/mcp","/skills","/exit"),registry.all().stream()
                .map(s->s.command().token()).collect(Collectors.toSet()));
        assertEquals(SlashCommand.EXIT, parser.parse("/quit", false).command());
    }

    @Test void validatesSyntaxSelectorsAndRunningAdmission(){
        assertEquals("task-1",parser.parse("/resume task-1",false).arguments().getFirst());
        assertEquals(SlashCommand.STATUS,parser.parse(" /status ",true).command());
        assertEquals(List.of("provider"),
                parser.parse("/status provider",true).arguments());
        assertEquals(List.of("--details"),
                parser.parse("/status --details",true).arguments());
        assertThrows(IllegalArgumentException.class,
                ()->parser.parse("/status database",true));
        assertTrue(parser.parse("/resume",false).arguments().isEmpty());
        assertEquals(SlashCommand.SESSIONS, parser.parse("/sessions", false).command());
        assertEquals(SlashCommand.NEW, parser.parse("/new", false).command());
        assertEquals(List.of("continue","this"),parser.parse("/continue continue this",false).arguments());
        assertThrows(IllegalStateException.class,()->parser.parse("/continue work",true));
        assertEquals(List.of("recover"), parser.parse("/task recover", false).arguments());
        assertEquals(List.of("recover", "tsk_123456789abc4def8000000000000001"),
                parser.parse("/task recover tsk_123456789abc4def8000000000000001",
                        false).arguments());
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("/task resume", false));
        assertEquals(SlashCommand.MCP,parser.parse("/mcp",false).command());
        assertEquals(SlashCommand.MODE, parser.parse("/mode plan", true).command());
        assertThrows(IllegalArgumentException.class,()->parser.parse("/mode deny",true));
        assertThrows(IllegalStateException.class,()->parser.parse("/clear",true));
        assertThrows(IllegalArgumentException.class,()->parser.parse("/provider mock",false));
        assertEquals(SlashCommand.PROVIDER,
                parser.parse("/provider set ollama", false).command());
        assertEquals(SlashCommand.MODEL,
                parser.parse("/model set qwen3:8b", false).command());
        assertThrows(IllegalArgumentException.class,()->parser.parse("/tasks 101",false));
        assertEquals(SlashCommand.VERIFY, parser.parse("/verify status", true).command());
        assertEquals(java.util.List.of("auto", "--save", "project"),
                parser.parse("/verify auto --save project", false).arguments());
        assertEquals(java.util.List.of("--details", "snapshot-1"),
                parser.parse("/rewind --details snapshot-1", false).arguments());
        assertEquals(SlashCommand.VERBOSE,
                parser.parse("/verbose on", true).command());
        assertThrows(IllegalArgumentException.class, () -> parser.parse("/verify command", false));
        assertEquals(SlashCommand.DOCTOR,parser.parse("/doctor",true).command());
        assertEquals(List.of("20"), parser.parse("/history 20", true).arguments());
        assertEquals(List.of("clear"), parser.parse("/history clear", true).arguments());
        assertThrows(IllegalArgumentException.class, () -> parser.parse("/history 101", false));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("/history all", false));
        assertTrue(parser.parse("/compact",false).arguments().isEmpty());
        assertEquals(List.of("status"),parser.parse("/compact status",true).arguments());
        assertThrows(IllegalArgumentException.class,()->parser.parse(
                "/compact preview",false));
        assertThrows(IllegalArgumentException.class,()->parser.parse(
                "/compact apply",false));
    }

    @Test void boundsPastedInputAndArguments(){
        assertThrows(IllegalArgumentException.class,()->parser.parse("/model set "+"x".repeat(300),false));
        assertThrows(IllegalArgumentException.class,()->parser.parse("/help"+" ".repeat(SlashCommandParser.MAX_INPUT_CHARS),false));
    }
    @Test void suggestsTyposAndNeverInterpretsShellSyntax(){
        IllegalArgumentException typo=assertThrows(IllegalArgumentException.class,()->parser.parse("/stats",false));
        assertTrue(typo.getMessage().contains("/status"));
        assertThrows(IllegalArgumentException.class,()->parser.parse("/help;rm",false));
        assertEquals("value;still-data",parser.parse("/model set value;still-data",false).arguments().get(1));
    }
}
