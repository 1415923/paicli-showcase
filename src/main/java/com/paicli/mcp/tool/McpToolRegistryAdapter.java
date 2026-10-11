package com.paicli.mcp.tool;

import com.paicli.mcp.lifecycle.McpLifecycleSession;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.runtime.McpLiveSessionRegistry;
import com.paicli.state.*;
import com.paicli.tool.ToolRegistry;

import java.util.*;

public final class McpToolRegistryAdapter {
    private final McpToolCatalogStore catalogs;private final McpToolStore tools;private final McpLiveSessionRegistry live;
    public McpToolRegistryAdapter(McpToolCatalogStore catalogs,McpToolStore tools,McpLiveSessionRegistry live){this.catalogs=catalogs;this.tools=tools;this.live=live;}
    public ToolRegistry derive(ToolRegistry parent,McpLifecycleSession lifecycle,McpToolCallService service,String occurrencePrefix)throws Exception{
        if(!new McpConfigManifestBuilder().build(lifecycle.config()).configHash().equals(lifecycle.manifest().configHash()))
            throw new IllegalStateException("MCP config manifest drifted");
        var session=lifecycle.persisted().store().findById(lifecycle.persisted().sessionId()).orElseThrow();if(!"READY".equals(session.status())||!lifecycle.persisted().isProcessAlive())throw new IllegalStateException("MCP session is not READY");
        live.requireUsable(session);
        var catalog=catalogs.findFrozenCatalog(session.sessionId()).orElseThrow(()->new IllegalStateException("MCP catalog is not FROZEN"));
        if(lifecycle.listChangedObserved())throw new IllegalStateException("MCP catalog is STALE");List<McpToolStore.ToolRecord> exposed=tools.findExposedToolsByCatalog(catalog.catalogId());
        Set<String> names=new HashSet<>();for(var t:parent.allTools())names.add(t.name());for(var t:exposed)if(!names.add(t.exposedName()))throw new IllegalArgumentException("MCP tool name collision: "+t.exposedName());
        ToolRegistry derived=new ToolRegistry();for(var t:parent.allTools())derived.register(t);for(var t:exposed)derived.register(new McpRegisteredTool(t,service,occurrencePrefix));return derived;
    }
}
