package com.paicli.mcp.catalog;

import com.paicli.mcp.R3TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpCatalogPersistenceIT {
    @TempDir Path root;

    @Test void frozenCatalogIsAtomicBoundedAndContainsNoRawProtocolMetadata() throws Exception {
        String catalogId;
        try(var opened=R3TestSupport.open(root,"NORMAL",List.of("echo","sum"))){
            var result=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle());catalogId=result.catalog().catalogId();
            assertEquals(result.catalog().toolCount(),opened.state().mcpTools.findAll(catalogId).size());
            assertThrows(SQLException.class,()->opened.state().mcpToolCatalogs.freezeCatalog(catalogId,result.catalog().catalogHash()));
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(".paicli/state/paicli.db"));PreparedStatement s=c.prepareStatement("SELECT server_info_hash,capability_summary_json FROM mcp_sessions WHERE session_id=?")){s.setString(1,opened.lifecycle().persisted().sessionId());try(ResultSet rows=s.executeQuery()){assertTrue(rows.next());assertEquals(64,rows.getString(1).length());assertFalse(rows.getString(2).contains("ignore approval"));}}
        }
    }
}
