package com.paicli.mcp.catalog;

import com.paicli.mcp.R3TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpToolsListChangedIT {
    @TempDir Path root;

    @Test void declaredNotificationMakesFrozenCatalogStaleWithoutRefresh() throws Exception {
        try(var opened=R3TestSupport.open(root,"LIST_CHANGED_AFTER_FROZEN",List.of("echo"))){
            var service=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools);
            var built=service.build(opened.lifecycle()); Thread.sleep(500);
            var stale=opened.state().mcpToolCatalogs.findById(built.catalog().catalogId()).orElseThrow();
            assertEquals("STALE",stale.status());
            assertEquals(built.tools(),opened.state().mcpTools.findAll(stale.catalogId()));
            assertEquals("CATALOG_STALE",assertThrows(McpCatalogException.class,()->service.build(opened.lifecycle())).category());
        }
    }

    @Test void notificationDuringBuildPreventsFreeze() throws Exception {
        try(var opened=R3TestSupport.open(root,"LIST_CHANGED_DURING_BUILD",List.of("echo"))){
            assertEquals("CATALOG_CHANGED_DURING_BUILD",assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle())).category());
            assertEquals("INVALID",opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).orElseThrow().status());
        }
    }

    @Test void undeclaredNotificationMakesSessionUnhealthy() throws Exception {
        try(var opened=R3TestSupport.open(root,"UNDECLARED_LIST_CHANGED",List.of("echo"))){
            var service=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools); service.build(opened.lifecycle()); Thread.sleep(500);
            assertEquals(com.paicli.mcp.jsonrpc.McpJsonRpcSession.State.UNHEALTHY,opened.lifecycle().persisted().jsonRpc().state());
            assertEquals("STALE",opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).orElseThrow().status());
        }
    }

    @Test void notificationBetweenInitializedAndCatalogOwnerIsNotLost() throws Exception {
        try(var opened=R3TestSupport.open(root,"EARLY_LIST_CHANGED",List.of("echo"))){
            Thread.sleep(200);
            assertTrue(opened.lifecycle().listChangedObserved());
            assertEquals("CATALOG_STALE_BEFORE_BUILD",assertThrows(McpCatalogException.class,()->new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle())).category());
            assertTrue(opened.state().mcpToolCatalogs.findBySession(opened.lifecycle().persisted().sessionId()).isEmpty());
        }
    }
}
