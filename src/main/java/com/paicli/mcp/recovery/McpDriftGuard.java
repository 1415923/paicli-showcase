package com.paicli.mcp.recovery;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.manifest.*;
import com.paicli.state.*;

import java.util.Objects;

public final class McpDriftGuard {
    private final McpConfigManifestBuilder builder = new McpConfigManifestBuilder();
    private final McpManifestComparator comparator = new McpManifestComparator();

    public McpManifestDrift requireManifestMatch(McpServerConfig config, McpConfigManifest bound) {
        final McpManifestDrift drift;
        try { drift = comparator.compare(bound, builder.build(config)); }
        catch (java.io.IOException e) { throw new IllegalStateException("MANIFEST_REBUILD_FAILED", e); }
        if (drift != McpManifestDrift.MATCH) throw new IllegalStateException(drift.name());
        return drift;
    }

    public void requireCatalogAndTool(McpSessionStore.SessionRecord session,
                                      McpToolCatalogStore.CatalogRecord catalog,
                                      McpToolStore.ToolRecord tool,
                                      McpConfigManifest bound) {
        Objects.requireNonNull(session); Objects.requireNonNull(catalog); Objects.requireNonNull(tool);
        if (!"READY".equals(session.status())) throw new IllegalStateException("SESSION_NOT_READY");
        if (!"FROZEN".equals(catalog.status())) throw new IllegalStateException("CATALOG_STALE_OR_INVALID");
        if (!session.sessionId().equals(catalog.sessionId()) || !session.manifestId().equals(catalog.manifestId()))
            throw new IllegalStateException("CATALOG_IDENTITY_DRIFT");
        if (!catalog.catalogId().equals(tool.catalogId()) || !"EXPOSED".equals(tool.exposureStatus()))
            throw new IllegalStateException("TOOL_IDENTITY_DRIFT");
        if (!tool.exposedName().equals("mcp." + session.serverId() + "." + tool.originalName()))
            throw new IllegalStateException("TOOL_NAME_DRIFT");
        if (!bound.serverId().equals(session.serverId())) throw new IllegalStateException("SERVER_CONFIG_DRIFT");
    }
}