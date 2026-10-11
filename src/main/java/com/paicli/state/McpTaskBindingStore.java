package com.paicli.state;

import java.sql.*;
import java.util.Optional;

/** Immutable offered-tool input for a Task, including Tasks that made no MCP calls. */
public final class McpTaskBindingStore {
    private final Connection connection;
    public McpTaskBindingStore(Connection connection) { this.connection=connection; }
    public record Binding(String task, String server, String manifest, String catalog) { }
    public Optional<Binding> find(String task) throws SQLException {
        try(var statement=connection.prepareStatement("SELECT * FROM mcp_task_bindings WHERE task_id=?")) {
            statement.setString(1,task);
            try(var row=statement.executeQuery()) { return row.next()?Optional.of(new Binding(task,
                    row.getString("server_id"),row.getString("manifest_hash"),row.getString("catalog_hash"))):Optional.empty(); }
        }
    }
    public void bind(Binding binding) throws SQLException {
        var prior=find(binding.task());
        if(prior.isPresent()) {
            if(!prior.get().equals(binding)) throw new IllegalStateException("MCP_TASK_BINDING_DRIFT");
            return;
        }
        try(var statement=connection.prepareStatement("INSERT INTO mcp_task_bindings VALUES(?,?,?,?)")) {
            statement.setString(1,binding.task());statement.setString(2,binding.server());
            statement.setString(3,binding.manifest());statement.setString(4,binding.catalog());statement.executeUpdate();
        }
    }
    public boolean hasUncertainCalls(String task) throws SQLException {
        try(var s=connection.prepareStatement("SELECT 1 FROM mcp_tool_calls WHERE task_id=? AND status IN ('RUNNING','PENDING','TIMED_OUT','PROCESS_EXITED','PROTOCOL_ERROR','CANCELLED','UNKNOWN_OUTCOME') LIMIT 1")) {
            s.setString(1,task);try(var rows=s.executeQuery()){return rows.next();}
        }
    }
}
