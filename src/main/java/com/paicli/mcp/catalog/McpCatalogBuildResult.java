package com.paicli.mcp.catalog;

import com.paicli.state.McpToolCatalogStore;
import com.paicli.state.McpToolStore;
import java.util.List;

public record McpCatalogBuildResult(McpToolCatalogStore.CatalogRecord catalog,
                                    List<McpToolStore.ToolRecord> tools,
                                    boolean reused) { }
