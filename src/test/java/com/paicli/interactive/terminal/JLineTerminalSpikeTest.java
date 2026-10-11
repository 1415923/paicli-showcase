package com.paicli.interactive.terminal;

import com.paicli.event.*;
import com.paicli.interactive.command.SlashCommandRegistry;
import com.paicli.interactive.render.InteractiveEventRenderer;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JLineTerminalSpikeTest {
    @Test void createsReaderWithPlainFallbackAndCompletesSlashCommands() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(var context=new InteractiveTerminalFactory().create(new ByteArrayInputStream(new byte[0]),output,true)){
            assertNotNull(context.reader());assertTrue(context.plain());
            List<String> commands=new SlashCommandRegistry().all().stream().map(s->s.command().token()).toList();
            StringsCompleter completer=new StringsCompleter(commands);
            ParsedLine line=new DefaultParser().parse("/st",3);
            List<Candidate> candidates=new ArrayList<>();completer.complete(context.reader(),line,candidates);
            assertTrue(candidates.stream().map(Candidate::value).toList().contains("/status"));
        }
    }

    @Test void printAbovePreservesEventAsASeparateTranscriptLine() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(var context=new InteractiveTerminalFactory().create(new ByteArrayInputStream(new byte[0]),output,true)){
            AgentEvent event=new AgentEvents.PlanCreated(new AgentEventMetadata(AgentEventType.PLAN_CREATED,
                    "session-1","task-1","run-1",1,Instant.EPOCH),"inspect then verify");
            new InteractiveEventRenderer(context.reader()).accept(event);context.terminal().flush();
            String transcript=output.toString(StandardCharsets.UTF_8);
            assertTrue(transcript.contains("[Plan] 0 steps"));
        }
    }
}
