package com.paicli.interactive.runtime;

import com.paicli.mcp.tool.McpToolNames;
import com.paicli.model.ToolCall;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real browser effects, Skill evidence and an independent verifier in one production task. */
@EnabledIfSystemProperty(named="paicli.ecosystem.external",matches="true")
class EcosystemCompositionIT {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings={"PASSED","FAILED"})
    void modelClaimCannotReplaceVerificationOfBrowserEffect(String expectedStatus)throws Exception {
        Path workspace=Files.createDirectory(temporary.resolve("workspace"));
        Path source=Files.createDirectories(temporary.resolve("browser-regression/references")).getParent();
        for(String relative:List.of("SKILL.md","references/report.md")){
            try(var input=getClass().getResourceAsStream("/skill-examples/browser-regression/"+relative)){
                assertNotNull(input);Files.copy(input,source.resolve(relative));
            }
        }
        Path observed=workspace.resolve("browser-observed.txt");
        assertFalse(Files.exists(observed));
        var failures=new AtomicInteger();var applied=new AtomicInteger();
        var http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();boolean failure=path.equals("/api/failure");
            boolean apply=path.equals("/api/apply");
            if(failure)failures.incrementAndGet();
            if(apply){
                String value=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                Files.writeString(observed,"APPLIED:"+value+System.lineSeparator());applied.incrementAndGet();
            }
            byte[] body=(failure?"fixture failure":apply?"recorded":"""
                    <!doctype html><title>MCP acceptance fixture</title><h1>Invoice INV-2047</h1>
                    <input aria-label="Filter" oninput="document.querySelector('output').textContent=this.value">
                    <button onclick="fetch('/api/apply',{method:'POST',body:document.querySelector('input').value}).then(()=>document.querySelector('output').textContent='FILTER_APPLIED')">Apply</button>
                    <output>initial</output><script>console.error('FIXTURE_CONSOLE_ERROR');fetch('/api/failure');</script>
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",failure||apply?"text/plain":"text/html; charset=utf-8");
            exchange.sendResponseHeaders(failure?503:200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        http.start();
        try{
            var calls=List.of(
                    new ToolCall("load_skill",Map.of("name","browser-regression")),
                    new ToolCall("read_skill_resource",Map.of("name","browser-regression","path","references/report.md")),
                    new ToolCall(McpToolNames.alias("chrome","new_page"),Map.of("url","http://127.0.0.1:"+http.getAddress().getPort())),
                    new ToolCall(McpToolNames.alias("chrome","take_snapshot"),Map.of("pageId",-1)),
                    new ToolCall(McpToolNames.alias("chrome","fill"),Map.of("pageId",-1,"uid","__FILTER__","value","INV-2047","includeSnapshot",true)),
                    new ToolCall(McpToolNames.alias("chrome","click"),Map.of("pageId",-1,"uid","__APPLY__","includeSnapshot",true)),
                    new ToolCall(McpToolNames.alias("chrome","list_console_messages"),Map.of("pageId",-1)),
                    new ToolCall(McpToolNames.alias("chrome","list_network_requests"),Map.of("pageId",-1)));
            String expected=expectedStatus.equals("PASSED")?"APPLIED:INV-2047":"APPLIED:INTENTIONALLY_WRONG";
            String command=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
                    ?"findstr /x /c:\""+expected+"\" browser-observed.txt":"grep -Fx '"+expected+"' browser-observed.txt";
            new McpEcosystemProductionIT().execute(workspace,"chrome",
                    List.of("--headless","--isolated","--no-usage-statistics","--no-performance-crux",
                            "--executablePath="+System.getProperty("paicli.ecosystem.chrome")),
                    List.of("new_page","take_snapshot","fill","click","list_console_messages","list_network_requests"),
                    calls,Collections.nCopies(6,"SUCCEEDED"),
                    List.of("Browser regression result","INV-2047","FIXTURE_CONSOLE_ERROR","503"),
                    new McpEcosystemProductionIT.Composition(source,command,expectedStatus,"joint-"+expectedStatus.toLowerCase(Locale.ROOT)));
            assertEquals(1,failures.get());assertEquals(1,applied.get());
            assertEquals("APPLIED:INV-2047",Files.readString(observed).strip());
        }finally{http.stop(0);}
    }
}
