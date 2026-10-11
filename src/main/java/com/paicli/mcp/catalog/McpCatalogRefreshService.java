package com.paicli.mcp.catalog;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.lifecycle.*;
import com.paicli.mcp.manifest.*;
import com.paicli.mcp.runtime.*;
import com.paicli.state.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class McpCatalogRefreshService {
    private final McpLifecycleService lifecycleService;
    private final McpToolCatalogService catalogService;
    private final McpServerManifestStore manifests;
    private final McpSessionStore sessions;
    private final McpToolCatalogStore catalogs;
    private final McpLiveSessionRegistry live;
    private final Set<String> refreshOwners=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String,RefreshResult> completedRefreshes=new ConcurrentHashMap<>();

    public McpCatalogRefreshService(McpLifecycleService lifecycleService, McpToolCatalogService catalogService,
                                    McpServerManifestStore manifests, McpSessionStore sessions,
                                    McpToolCatalogStore catalogs, McpLiveSessionRegistry live) {
        this.lifecycleService=Objects.requireNonNull(lifecycleService); this.catalogService=Objects.requireNonNull(catalogService);
        this.manifests=Objects.requireNonNull(manifests); this.sessions=Objects.requireNonNull(sessions);
        this.catalogs=Objects.requireNonNull(catalogs); this.live=Objects.requireNonNull(live);
    }

    public RefreshResult refreshCatalogExplicitly(String staleCatalogId, McpServerConfig currentConfig,
                                                   String runScope, String occurrence, int attempt,
                                                   Map<String,String> hostEnvironment) throws Exception {
        RefreshResult completed=completedRefreshes.get(staleCatalogId); if(completed!=null)return completed;
        if (!refreshOwners.add(staleCatalogId)) throw new IllegalStateException("REFRESH_IN_PROGRESS");
        completed=completedRefreshes.get(staleCatalogId);
        if(completed!=null){refreshOwners.remove(staleCatalogId);return completed;}
        try {
            var old=catalogs.findById(staleCatalogId).orElseThrow();
            if (!"STALE".equals(old.status())) throw new IllegalStateException("EXPLICIT_REFRESH_REQUIRES_STALE_CATALOG");
            live.closeSession(old.sessionId());
            McpConfigManifest current=new McpConfigManifestBuilder().build(currentConfig);
            var manifest=manifests.saveOrFindManifest(current);
            String sessionId=UUID.randomUUID().toString();
            McpLifecycleSession lifecycle=lifecycleService.initialize(sessionId,manifest.manifestId(),runScope,
                    occurrence,attempt,currentConfig,current,sessions,hostEnvironment);
            try {
                McpCatalogBuildResult built=catalogService.build(lifecycle);
                var catalog=built.catalog();
                var persisted=sessions.findById(sessionId).orElseThrow();
                live.bind(new McpRuntimeSessionHandle(sessionId,persisted.processIdentityHash(),current.configHash(),
                        catalog.catalogId(),catalog.catalogHash(),lifecycle),persisted);
                RefreshResult result=new RefreshResult(old.catalogId(),sessionId,catalog.catalogId(),catalog.catalogHash(),"COMPLETED",false);
                completedRefreshes.put(staleCatalogId,result);return result;
            } catch (Exception e) {
                live.closeSession(sessionId);
                try { lifecycle.close(); } catch (Exception close) { e.addSuppressed(close); }
                throw e;
            }
        } finally { refreshOwners.remove(staleCatalogId); }
    }

    public record RefreshResult(String oldCatalogId,String newSessionId,String newCatalogId,String catalogHash,
                                String status,boolean automaticRegistryReplacement) {}
}