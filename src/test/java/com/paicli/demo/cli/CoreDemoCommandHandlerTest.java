package com.paicli.demo.cli;

import com.paicli.demo.artifact.CoreDemoArtifactWriter;
import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.runtime.*;
import com.paicli.demo.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoCommandHandlerTest {
    @TempDir Path root;
    @Test void handlerUsesFixedParserAndReturnsStableNonInteractiveExit() throws Exception {
        Path repo=root.resolve("repo");Files.createDirectory(repo);
        var workspaces=new CoreDemoWorkspaceService(root.resolve("runs"),repo);
        var preparation=new CoreDemoPreparationStub(workspaces,
                CoreDemoBaselineResult.Status.DEMO_BASELINE_INVALID);
        AtomicInteger calls=new AtomicInteger();
        var runner=new HeadlessCoreDemoRunner(preparation,workspaces,
                context->{calls.incrementAndGet();throw new AssertionError();},
                new CoreDemoArtifactWriter());
        var handler=new CoreDemoCommandHandler(new CoreDemoCommandParser(),runner,
                new CoreDemoOutput());
        StringWriter out=new StringWriter(),err=new StringWriter();
        int code=handler.execute(new String[]{"demo","core","--headless"},
                new PrintWriter(out),new PrintWriter(err));
        assertEquals(3,code);assertEquals(0,calls.get());assertEquals("",out.toString());
        assertTrue(err.toString().contains("\"exitCode\":3"));
        assertFalse(err.toString().contains(root.toString()));
        StringWriter usage=new StringWriter();
        assertEquals(2,handler.execute(new String[]{"demo","core"},
                new PrintWriter(new StringWriter()),new PrintWriter(usage)));
        assertTrue(usage.toString().contains("\"exitCode\":2"));
    }
}
