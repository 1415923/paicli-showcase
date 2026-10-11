package com.paicli.state;

import com.paicli.mcp.manifest.McpConfigManifest;
import java.sql.*;
import java.util.Optional;
import java.util.UUID;

public final class McpServerManifestStore {
    private final Connection c;
    public McpServerManifestStore(Connection c){this.c=c;}

    public ManifestRecord saveOrFindManifest(McpConfigManifest m) throws SQLException {
        Optional<ManifestRecord> existing=findByServerAndHash(m.serverId(),m.configHash());
        if(existing.isPresent()) return existing.get();
        String id=UUID.randomUUID().toString();
        try(PreparedStatement s=c.prepareStatement("""
          INSERT INTO mcp_server_manifests VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,datetime('now'))
          """)){
            int i=1;s.setString(i++,id);s.setString(i++,m.serverId());s.setString(i++,m.manifestFormatVersion());s.setString(i++,m.transport());s.setString(i++,m.protocolVersionsJson());s.setString(i++,m.configHash());s.setString(i++,m.executableIdentityHash());s.setString(i++,m.executableContentHash());s.setString(i++,m.executableBasename());s.setString(i++,m.workingDirectoryIdentityHash());s.setString(i++,m.argsHash());s.setString(i++,m.environmentReferencesJson());s.setString(i++,m.toolAllowlistJson());s.setInt(i++,m.startupTimeoutMs());s.setInt(i++,m.requestTimeoutMs());s.setInt(i++,m.shutdownTimeoutMs());s.setInt(i++,m.maxPendingRequests());s.setInt(i++,m.maxStdoutLineBytes());s.setInt(i++,m.maxStderrBytes());s.setInt(i++,m.maxCatalogPages());s.setInt(i++,m.maxTools());s.setInt(i++,m.maxCatalogBytes());s.setInt(i++,m.maxSingleSchemaBytes());s.setInt(i++,m.maxOutputBytes());s.setString(i++,m.approvalPolicyVersion());s.setString(i++,m.sanitizerPolicyVersion());s.setString(i,m.schemaPolicyVersion());s.executeUpdate();
        } catch(SQLException e){ existing=findByServerAndHash(m.serverId(),m.configHash()); if(existing.isPresent()) return existing.get(); throw e; }
        return findManifestById(id).orElseThrow();
    }
    public Optional<ManifestRecord> findManifestById(String id)throws SQLException{return one("manifest_id = ?",id);}
    public Optional<ManifestRecord> findLatestManifestByServer(String id)throws SQLException{
        try(PreparedStatement s=c.prepareStatement(SELECT+" WHERE server_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1")){s.setString(1,id);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();}}
    }
    public boolean manifestMatchesCurrentConfig(String id,String hash)throws SQLException{return findManifestById(id).map(r->r.configHash().equals(hash)).orElse(false);}
    private Optional<ManifestRecord> findByServerAndHash(String server,String hash)throws SQLException{try(PreparedStatement s=c.prepareStatement(SELECT+" WHERE server_id=? AND config_hash=?")){s.setString(1,server);s.setString(2,hash);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();}}}
    private Optional<ManifestRecord> one(String where,String value)throws SQLException{try(PreparedStatement s=c.prepareStatement(SELECT+" WHERE "+where)){s.setString(1,value);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();}}}
    private ManifestRecord map(ResultSet r)throws SQLException{return new ManifestRecord(r.getString("manifest_id"),r.getString("server_id"),r.getString("config_hash"),r.getString("transport"),r.getString("protocol_versions_json"),r.getString("executable_content_hash"),r.getString("executable_basename"),r.getString("approval_policy_version"),r.getString("sanitizer_policy_version"),r.getString("schema_policy_version"),r.getString("created_at"));}
    private static final String SELECT="SELECT * FROM mcp_server_manifests";
    public record ManifestRecord(String manifestId,String serverId,String configHash,String transport,String protocolVersionsJson,String executableContentHash,String executableBasename,String approvalPolicyVersion,String sanitizerPolicyVersion,String schemaPolicyVersion,String createdAt){}
}
