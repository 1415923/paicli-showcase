package com.paicli.mcp.catalog;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.config.McpRuntimeLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class McpToolCatalogServiceIT {
    @TempDir Path root;

    @Test void paginatesNormalizesFreezesAndReuses() throws Exception {
        try (var opened = R3TestSupport.open(root, "NORMAL", List.of("echo","sum"))) {
            var service = new McpToolCatalogService(opened.state().mcpToolCatalogs, opened.state().mcpTools);
            var first = service.build(opened.lifecycle());
            assertEquals("FROZEN", first.catalog().status()); assertEquals(2, first.catalog().pageCount());
            assertEquals(List.of("echo","sum"), first.tools().stream().map(x -> x.originalName()).toList());
            assertTrue(first.tools().stream().allMatch(x -> x.exposureStatus().equals("EXPOSED")));
            assertTrue(service.build(opened.lifecycle()).reused());
        }
    }

    @Test void unallowlistedToolsArePersistedRejectedButNotExposed() throws Exception {
        try (var opened = R3TestSupport.open(root, "EXTRA_TOOL", List.of("echo"))) {
            var result = new McpToolCatalogService(opened.state().mcpToolCatalogs, opened.state().mcpTools).build(opened.lifecycle());
            assertEquals(2, result.tools().size());
            assertEquals(List.of("echo"), opened.state().mcpTools.findExposedToolsByCatalog(result.catalog().catalogId()).stream().map(x -> x.originalName()).toList());
            assertEquals("NOT_ALLOWLISTED", result.tools().stream().filter(x -> x.originalName().equals("extra")).findFirst().orElseThrow().rejectionCategory());
        }
    }

    @Test void rejectsCursorDuplicateMissingAllowlistUnsafeNameSchemaAndTaskSupport() throws Exception {
        var cases = Map.of("CURSOR_LOOP","CURSOR_CYCLE", "DUPLICATE_TOOL","DUPLICATE_TOOL_NAME",
                "INVALID_TOOL_NAME","INVALID_TOOL_NAME",
                "TASK_REQUIRED","UNSUPPORTED_TASK_EXECUTION");
        for (var item : cases.entrySet()) {
            Path dir=root.resolve(item.getKey()); java.nio.file.Files.createDirectories(dir);
            try (var opened=R3TestSupport.open(dir,item.getKey(),List.of(item.getKey().equals("CURSOR_LOOP")?"loop":"echo"))) {
                var error=assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()));
                assertEquals(item.getValue(),error.category(),item.getKey());
                assertEquals("INVALID",opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).orElseThrow().status());
                assertTrue(opened.state().mcpTools.findExposedToolsByCatalog(opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).orElseThrow().catalogId()).isEmpty());
            }
        }
        try(var opened=R3TestSupport.open(root.resolve("missing"),"SINGLE_PAGE",List.of("missing"))){assertEquals("ALLOWLIST_TOOL_MISSING",assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle())).category());}
    }

    @Test void unsupportedSchemaHasVisibleRejectionWithoutExposingWeakenedSchema() throws Exception {
        try(var opened=R3TestSupport.open(root,"UNSUPPORTED_SCHEMA",List.of("echo"))) {
            var result=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle());
            assertEquals("FROZEN",result.catalog().status());
            assertEquals("REJECTED",result.tools().getFirst().exposureStatus());
            assertNotNull(result.tools().getFirst().rejectionCategory());
            assertTrue(opened.state().mcpTools.findExposedToolsByCatalog(result.catalog().catalogId()).isEmpty());
        }
    }

    @Test void concurrentBuildHasOneCatalogOwner() throws Exception {
        try (var opened=R3TestSupport.open(root,"NORMAL",List.of("echo","sum"))) {
            var service=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools);
            var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
            Callable<Object> call=()->{start.await();try{return service.build(opened.lifecycle());}catch(McpCatalogException e){return e.category();}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();Object ra=a.get(10,TimeUnit.SECONDS),rb=b.get(10,TimeUnit.SECONDS);pool.shutdownNow();
            assertTrue(ra instanceof McpCatalogBuildResult || rb instanceof McpCatalogBuildResult);
            var catalog=opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).orElseThrow();
            assertEquals("FROZEN",catalog.status());assertEquals(2,opened.state().mcpTools.findAll(catalog.catalogId()).size());
        }
    }

    @Test void enforcesPageToolAndCatalogByteBudgets() throws Exception {
        McpRuntimeLimits d=com.paicli.mcp.config.McpRuntimeLimits.defaults();
        var pageLimit=new McpRuntimeLimits(d.startupTimeoutMs(),d.requestTimeoutMs(),d.shutdownTimeoutMs(),d.maxPendingRequests(),d.maxStdoutLineBytes(),d.maxStderrBytes(),1,d.maxTools(),d.maxCatalogBytes(),d.maxSingleSchemaBytes(),d.maxOutputBytes());
        try(var opened=R3TestSupport.open(root.resolve("pages"),"NORMAL",List.of("echo","sum"),pageLimit)){assertEquals("MAX_CATALOG_PAGES",assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle())).category());}
        var toolLimit=new McpRuntimeLimits(d.startupTimeoutMs(),d.requestTimeoutMs(),d.shutdownTimeoutMs(),d.maxPendingRequests(),d.maxStdoutLineBytes(),d.maxStderrBytes(),d.maxCatalogPages(),1,d.maxCatalogBytes(),d.maxSingleSchemaBytes(),d.maxOutputBytes());
        try(var opened=R3TestSupport.open(root.resolve("tools"),"EXTRA_TOOL",List.of("echo"),toolLimit)){assertEquals("MAX_TOOLS",assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle())).category());}
        var byteLimit=new McpRuntimeLimits(d.startupTimeoutMs(),d.requestTimeoutMs(),d.shutdownTimeoutMs(),d.maxPendingRequests(),d.maxStdoutLineBytes(),d.maxStderrBytes(),d.maxCatalogPages(),d.maxTools(),4096,d.maxSingleSchemaBytes(),d.maxOutputBytes());
        try(var opened=R3TestSupport.open(root.resolve("bytes"),"OVERSIZED_CATALOG",List.of("huge"),byteLimit)){assertEquals("CATALOG_TOO_LARGE",assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle())).category());}
    }

    @Test void processExitDuringPaginationInvalidatesPartialCatalog() throws Exception {
        try(var opened=R3TestSupport.open(root,"EXIT_DURING_PAGINATION",List.of("echo","sum"))){
            assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()));
            var catalog=opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).orElseThrow();
            assertEquals("INVALID",catalog.status());assertTrue(opened.state().mcpTools.findAll(catalog.catalogId()).isEmpty());
        }
    }
}
